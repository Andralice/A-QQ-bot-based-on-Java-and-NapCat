package com.start.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.start.agent.KnowledgeBaseTool;
import com.start.agent.Tool;
import com.start.agent.WeatherTool;
import com.start.service.BaiLianService;
import com.start.Main;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;


public class AgentService  {

    private static final Logger logger = LoggerFactory.getLogger(AgentService.class);
    private final BaiLianService aiService;
    private final List<Tool> availableTools;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AgentService(BaiLianService aiService, KeywordKnowledgeService knowledgeService) {
        this.aiService = aiService;
        this.availableTools = Arrays.asList(
                new KnowledgeBaseTool(knowledgeService),
                new WeatherTool()

                // 可扩展：new WebSearchTool(), new CalculatorTool()...
        );
    }

    /**
     * 完整的 Agent 处理流程：支持工具调用 + 结果生成
     */
    public String process(String userPrompt, String userId, String groupId) {
        logger.info("🧠 Agent 开始处理: prompt=[{}]", userPrompt);

        try {
            // Step 1: 让 AI 决策是否需要调用工具
            JsonNode response = aiService.generateWithTools(userPrompt, availableTools);

            // 提取 content（可能为空，也可能有追问）
            String content = response.path("content").asText().trim();
            boolean hasToolCalls = response.has("tool_calls")
                    && response.get("tool_calls").isArray()
                    && !response.get("tool_calls").isEmpty();

            if (hasToolCalls) {
                // 执行工具调用流程
                JsonNode toolCall = response.get("tool_calls").get(0);
                String toolName = toolCall.path("function").path("name").asText();
                String argsJson = toolCall.path("function").path("arguments").asText();

                Tool tool = availableTools.stream()
                        .filter(t -> t.getName().equals(toolName))
                        .findFirst()
                        .orElse(null);

                if (tool != null) {
                    Map<String, Object> args = objectMapper.readValue(argsJson, Map.class);
                    String toolResult = tool.execute(args);
                    logger.info("🔧 工具 [{}] 执行结果: {}", toolName, toolResult);

                    // Step 2: 用工具结果生成最终回答
                    String finalPrompt = "用户问题：" + userPrompt + "\n\n工具返回结果：" + toolResult;
                    return aiService.generateForAgent(finalPrompt, Collections.emptyList());
                }
            }

            // ✅ 修复点：无论是否有 content，都直接返回它！
            // 即使是追问（如“请提供城市”），也是有效回复
            if (!content.isEmpty()) {
                return content;
            }

            // 极端情况：content 为空且无 tool_calls（理论上不该发生）
            return "无法提供相关信息。";

        } catch (JsonProcessingException e) {
            logger.error("Agent 解析工具参数失败", e);
            return "参数解析错误，请重试。";
        } catch (Exception e) {
            logger.error("Agent 处理异常", e);
            return "抱歉，暂时无法完成这个操作。";
        }
    }
}