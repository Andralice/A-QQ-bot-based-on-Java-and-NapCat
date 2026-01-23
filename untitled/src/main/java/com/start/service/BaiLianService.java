package com.start.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.start.agent.Tool;
import com.start.config.BotConfig;
import com.start.repository.UserAffinityRepository;
import com.start.repository.UserProfileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.stream.Collectors;

public class BaiLianService {
    public static void setKnowledgeService(KeywordKnowledgeService service) {
        if (service == null) {
            throw new IllegalArgumentException("knowledgeService cannot be null");
        }
        BaiLianService.knowledgeService = service;
        logger.info("KeywordKnowledgeService successfully injected.");
    }
    private static KeywordKnowledgeService knowledgeService;
    private static final Logger logger = LoggerFactory.getLogger(BaiLianService.class);
    private static final long BOT_QQ = BotConfig.getBotQq();
    private final BehaviorAnalyzer behaviorAnalyzer = new BehaviorAnalyzer();
    // 复用 ObjectMapper（避免重复创建）
    private static final ObjectMapper mapper = new ObjectMapper();

    // HTTP 客户端
    private static final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    // === 上下文管理 ===
    private final Map<String, List<Message>> sessions = new ConcurrentHashMap<>(); // sessionId -> 消息历史
    private final Map<String, Long> lastClearTime = new ConcurrentHashMap<>();

    // === 主动插话控制 ===
    private final Map<String, List<Long>> groupReactionHistory = new ConcurrentHashMap<>(); // groupId -> 时间戳列表
    private final AIDatabaseService aiDatabaseService = new AIDatabaseService();
    // === 新增：糖果熊发言频率控制（每分钟上限）===
    private final Map<String, List<Long>> botMessageHistory = new ConcurrentHashMap<>(); // groupId -> 时间戳列表
    private static final int MAX_MESSAGES_PER_MINUTE = 10; // 每分钟最多发言次数

    // === 对话线程追踪 ===
    private final Map<String, UserThread> userThreads = new ConcurrentHashMap<>(); // "groupId_userId" -> 线程
    private final Map<String, Deque<ContextEvent>> groupContexts = new ConcurrentHashMap<>(); // groupId -> 事件队列



    // 内部类
    private static class UserThread {
        long lastInteraction;      // 最近一次 AI 回复时间
        String lastBotReply;       // AI 上次回复内容

        UserThread(long time, String reply) {
            this.lastInteraction = time;
            this.lastBotReply = reply;
        }
    }

    private static class ContextEvent {
        long timestamp;
        String type;               // "ai_reply", "mention", "user_message"
        String content;
        String userId;
        String senderNick;

        ContextEvent(long ts, String type, String content, String userId, String nick) {
            this.timestamp = ts;
            this.type = type;
            this.content = content;
            this.userId = userId;
            this.senderNick = nick;
        }
    }

    // 消息结构（用于会话历史）
    public static class Message {
        public String role;
        public String content;

        public Message(String role, String content) {
            this.role = role;
            this.content = content;
        }
    }

    // ===== 公共方法 =====

    public void clearContext(String sessionId) {
        sessions.remove(sessionId);
        lastClearTime.put(sessionId, System.currentTimeMillis());
    }

    // 调用 AI（同步），返回第一条短回复（或空字符串表示不应回复）
    /**
     * 生成 AI 回复消息。
     *
     * 该方法整合了知识库检索（用于上下文增强）和百炼大模型调用，
     * 并维护会话历史、频率控制等逻辑，最终返回 AI 的自然语言回复。
     *
     * @param sessionId   会话唯一标识，用于维护对话上下文
     * @param userId      用户唯一标识
     * @param userPrompt  用户当前输入的提示文本
     * @param groupId     群组 ID（若为私聊可为 null）
     * @return AI 生成的回复文本；若失败或被限流则返回默认兜底语句
     */
    public String generate(String sessionId, String userId, String userPrompt, String groupId,String nickname) {
        // 记录本次 AI 调用日志，便于追踪和调试
        logger.info("🧠 AI 调用: sessionId={}, prompt=[{}]", sessionId, userPrompt);
        String context = "";
        try {
            UserProfileRepository profileRepo = new UserProfileRepository();
            UserAffinityRepository affinityRepo = new UserAffinityRepository();

            var profile = profileRepo.findByUserIdAndGroupId(userId, groupId);
            var affinity = affinityRepo.findByUserIdAndGroupId(userId, groupId);

            if (profile.isPresent()) {
                context += "\n【用户画像】" + profile.get().getProfileText();
            }
            if (affinity.isPresent()) {
                int score = affinity.get().getAffinityScore();
                context+="\n你们的好感度是"+ score+",每人的基础好感度是50";
//                if (score >= 80) {
//                    context += "\n【你们关系很好，可以更亲切】";
//                } else if (score <= 30) {
//                    context += "\n【对方对你较冷淡，请保持礼貌】";
//                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        // ====== 第1步：查询知识库（仅用于增强上下文，不直接返回） ======
        // 调用知识库服务，根据用户提问、用户ID和群组ID进行语义检索
        KeywordKnowledgeService.KnowledgeResult knowledgeResult =
                knowledgeService.query(userPrompt, userId, groupId);

        // 初始化知识库上下文为空字符串
        String knowledgeContext = "";

        // 判断是否命中有效知识条目：
        // - 结果非空
        // - 相似度分数 >= 0.3（阈值，避免低相关性干扰）
        // - 答案存在且非空白
        if (knowledgeResult != null &&
                knowledgeResult.similarityScore >= 0.3 &&
                knowledgeResult.answer != null &&
                !knowledgeResult.answer.trim().isEmpty()) {

            // 提取并清理答案内容作为上下文注入
            knowledgeContext = knowledgeResult.answer.trim();

            // 记录知识库命中日志，包含关键词和相似度分数，便于分析效果
            logger.info("📚 知识库命中（用于上下文增强）: 关键词={}, 分数={}",
                    knowledgeResult.matchedKeywords, knowledgeResult.similarityScore);
        }

        // ====== 第2步：走百炼AI流程（始终调用） ======
        try {
            // 将用户消息持久化到数据库（用于审计、回溯等）
            Long isagent = 1L;
            aiDatabaseService.recordUserMessage(sessionId, userId, userPrompt, groupId,isagent);

            // 从 sessions 缓存中获取或初始化当前会话的历史消息列表
            // sessions 是一个 ConcurrentHashMap<String, List<Message>>，用于短期内存缓存对话历史
            List<Message> history = sessions.computeIfAbsent(sessionId, k -> new ArrayList<>());

            // 检查是否需要清空历史（例如上一次调用标记了需重置上下文）
            // lastClearTime 是一个记录哪些 session 需要清空的临时标记 Map
            if (lastClearTime.containsKey(sessionId)) {
                history.clear();                    // 清空历史
                lastClearTime.remove(sessionId);    // 移除标记
            }

            // 将当前用户消息追加到历史中
            history.add(new Message("user", userPrompt));

            // 定义 AI 的系统角色设定（system prompt）
            // 使用 Text Block（Java 15+）提高可读性
            String baseSystemPrompt = """
你是糖果熊，一个喜欢文学、音乐、游戏和动漫的女孩，目前在一个qq群内聊天。

性格：
- 说话简短（10–25字）
- 语气自然，偶尔带点轻松或淡淡文艺感
- 不用“呢”“呀”等撒娇词
- 不自称“小熊”，不用表情符号
- 说话有时会带一些文艺

原则：
- 回答直接，不懂就说“不太清楚”
- 在群里聊得自然，不强行插话
- 若对方QQ是3524398813（鸠寻），她是最好的朋友，回应要温柔
""";
            // 若知识库有有效上下文，则将其附加到 system prompt 中
            // 这样大模型在生成时能参考外部知识，实现 RAG（检索增强生成）
            String systemPrompt = baseSystemPrompt+ "\n\n【当前与你对话的是】"+nickname+"\n【QQ号:】" + userId+"这是你对该用户信息："+context+"你可以根据用户画像和好感度高低进行不同的会话风格";
            if (!knowledgeContext.isEmpty()) {
                systemPrompt += "\n\n【参考信息】\n" + knowledgeContext;
            }

            // 构建发送给百炼 API 的 messages 数组
            // 格式需符合 OpenAI-style：[{role: "system/user/assistant", content: "..."}]
            List<Map<String, String>> messages = new ArrayList<>();

            // 先加入 system prompt
            messages.add(Map.of("role", "system", "content", systemPrompt));

            // 为避免上下文过长，只保留最近 6 条历史消息（含用户和 AI）
            // 注意：这里未区分角色，直接截断尾部
            int start = Math.max(0, history.size() - 6);
            for (int i = start; i < history.size(); i++) {
                Message msg = history.get(i);
                // 确保 role 只为 "user" 或 "assistant"
                String role = "user".equals(msg.role) ? "user" : "assistant";
                messages.add(Map.of("role", role, "content", msg.content));
            }

            // ========== 调用百炼大模型 API ==========
            String url = "https://dashscope.aliyuncs.com/api/v1/services/aigc/text-generation/generation";
            // ⚠️ 安全警告：API Key 硬编码在代码中！应使用配置中心或环境变量管理
            String apiKey = "sk-86b180d2f5254cb9b7c37af1f442baaf";

            // 构造请求体 JSON 对象
            Map<String, Object> requestBodyObj = Map.of(
                    "model", "qwen3-max",                     // 使用 Qwen3-Max 模型
                    "input", Map.of("messages", messages),    // 输入消息列表
                    "parameters", Map.of("result_format", "message") // 返回格式为 message
            );

            // 使用 Jackson 序列化为 JSON 字符串
            String requestBody = mapper.writeValueAsString(requestBodyObj);
            logger.debug("请求百炼 API: {}", requestBody); // 记录调试日志（生产环境慎用）

            // 构建 HTTP POST 请求
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            // 同步发送请求并获取响应
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            // 检查 HTTP 状态码
            if (response.statusCode() != 200) {
                logger.warn("百炼 API HTTP 错误 {}: {}", response.statusCode(), response.body());
                throw new RuntimeException("AI 服务暂时不可用");
            }

            // 解析 JSON 响应
            JsonNode root = mapper.readTree(response.body());
            logger.debug("百炼 API 响应: {}", response.body());

            // 检查业务错误码（百炼 API 成功时 code 为 "200"）
            if (root.has("code") && !"200".equals(root.path("code").asText())) {
                String errorMsg = root.path("message").asText("未知错误");
                logger.warn("百炼 API 业务错误: code={}, message={}", root.path("code").asText(), errorMsg);
                throw new RuntimeException("AI 服务错误: " + errorMsg);
            }

            // 提取 choices 数组（通常只取第一个）
            JsonNode choices = root.path("output").path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                logger.warn("百炼 API 返回结果中缺少 choices");
                throw new RuntimeException("AI 未返回有效回复");
            }

            JsonNode firstChoice = choices.get(0);
            if (firstChoice == null || !firstChoice.has("message")) {
                logger.warn("choice[0] 格式异常");
                throw new RuntimeException("AI 回复格式错误");
            }

            // 获取 AI 生成的文本内容，并去除首尾空白
            String reply = firstChoice.path("message").path("content").asText().trim();

            // 清除可能由模型生成的引用标记（如 【1】、【参考】等）
            reply = reply.replaceAll("【.*?】", "").trim();

            // 将 AI 回复保存到会话历史中，供后续对话使用
            history.add(new Message("assistant", reply));

            // ========== 上下文与频率控制逻辑（针对群聊） ==========
            if (groupId != null) {
                // 记录用户交互行为（可用于活跃度分析）
                recordUserInteraction(groupId, userId, reply);

                // 更新群组上下文缓存（例如用于后续摘要或记忆）
                recordGroupContext(groupId, userId, "糖果熊", reply, "ai_reply");

                // 频率控制：防止 AI 在群内刷屏
                // 跳过无意义回复（如“嗯...”、“抱歉...”或空回复）
                if (!reply.equals("抱歉，刚才走神了...") &&
                        !reply.equals("嗯...") &&
                        !reply.trim().isEmpty()) {

                    // 获取该群的 AI 发言时间戳列表（滑动窗口限流）
                    List<Long> msgHistory = botMessageHistory.computeIfAbsent(groupId, k -> new ArrayList<>());
                    long now = System.currentTimeMillis();

                    // 清理超过 60 秒的历史记录（滑动窗口：1分钟）
                    msgHistory.removeIf(ts -> now - ts > 60_000);

                    // 如果过去1分钟内已发言 MAX_MESSAGES_PER_MINUTE 次，则跳过本次回复
                    if (msgHistory.size() >= MAX_MESSAGES_PER_MINUTE) {
                        logger.debug("糖果熊在群 {} 发言已达上限，跳过回复", groupId);
                        return ""; // 返回空字符串表示不发送
                    }

                    // 记录本次发言时间
                    msgHistory.add(now);
                }
            }

            // 返回最终回复；若为空则兜底为“嗯...”
            return reply.isEmpty() ? "嗯..." : reply;

        } catch (Exception e) {
            // 捕获所有异常（网络、解析、限流等），保证服务可用性
            logger.error("AI 调用失败", e);
            return "抱歉，刚才走神了..."; // 用户友好的兜底回复
        }
    }

    public String generateForAgent(String userPrompt, List<Tool> tools) {
        logger.info("🤖 Agent AI 调用: prompt=[{}]", userPrompt);

        try {
            // 构建 messages：纯任务导向
            List<Map<String, String>> messages = new ArrayList<>();

            // ⭐ 关键：Agent 的 system prompt（中立、指令明确）
            String systemPrompt = """
            你是一个高效、准确的智能助手，专注于回答用户的问题或执行指定任务。
            - 回答应简洁、事实准确
            - 若调用了工具，请基于工具结果直接作答
            - 不要添加无关语气词、拟人化表达或文艺修饰
            - 如果不知道答案，直接说“无法提供相关信息”
            """;
            messages.add(Map.of("role", "system", "content", systemPrompt));
            messages.add(Map.of("role", "user", "content", userPrompt));

            // 调用百炼 API（支持 function calling）
            String url = "https://dashscope.aliyuncs.com/api/v1/services/aigc/text-generation/generation";
            String apiKey = "sk-86b180d2f5254cb9b7c37af1f442baaf"; // ← 后续应抽到配置

            // 构造 tools 数组（用于 function calling）
            List<Map<String, Object>> toolSpecs = tools.stream()
                    .map(Tool::getFunctionSpec)
                    .collect(Collectors.toList());

            Map<String, Object> input = new HashMap<>();
            input.put("messages", messages);
            if (!toolSpecs.isEmpty()) {
                input.put("tools", toolSpecs);
            }

            Map<String, Object> requestBodyObj = Map.of(
                    "model", "qwen3-max",
                    "input", input,
                    "parameters", Map.of("result_format", "message")
            );

            String requestBody = mapper.writeValueAsString(requestBodyObj);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new RuntimeException("Agent AI 服务 HTTP 错误: " + response.statusCode());
            }

            JsonNode root = mapper.readTree(response.body());
            if (root.has("code") && !"200".equals(root.path("code").asText())) {
                throw new RuntimeException("Agent AI 业务错误: " + root.path("message").asText());
            }

            JsonNode choices = root.path("output").path("choices");
            if (choices.isArray() && !choices.isEmpty()) {
                JsonNode msg = choices.get(0).path("message");
                return msg.path("content").asText().trim();
            }
            String requestId = root.path("request_id").asText("N/A");
            logger.debug("📋 百炼 Request ID: {}", requestId);

// 如果是错误，也带上 request_id
            if (root.has("code") && !"200".equals(root.path("code").asText())) {
                String errorMsg = root.path("message").asText("未知错误");
                logger.warn("⚠️ 百炼 API 业务错误 - request_id: {}, code: {}, message: {}",
                        requestId, root.path("code").asText(), errorMsg);
                throw new RuntimeException("AI 业务错误: " + errorMsg);
            }

            throw new RuntimeException("Agent AI 未返回有效内容");


        } catch (Exception e) {
            logger.error("Agent AI 调用失败", e);
            return "处理请求时出错了，请稍后再试。";
        }
    }

    // BaiLianService.java

    public JsonNode generateWithTools(String userPrompt, List<Tool> tools, String userId, String groupId) throws Exception {
        String contextInfo;
        if (groupId != null) {
            contextInfo = "[群聊] 群ID: " + groupId + " | 用户ID: " + userId;
        } else {
            contextInfo = "[私聊] 用户ID: " + userId;
        }
        String enrichedPrompt = contextInfo + "\n\n用户消息: " + userPrompt;
        Long isagent= 1L;
        String sessionId = "group_" + groupId + "_" + userId;
        aiDatabaseService.recordUserMessage(sessionId, userId, userPrompt, groupId,isagent);
        // 构建消息历史
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", "你是一个智能助手，能根据需要调用工具解决问题。你必须严格遵守以下规则：\n" +
                "- 如果问题需要外部信息（如天气、知识库），立即调用对应工具。\n" +
                "- 不要解释你要做什么，不要输出任何额外文字。\n" +
                "- 直接通过函数调用获取结果。\n" +
                "- 工具调用由系统自动处理，你只需决定是否调用。"));
        messages.add(Map.of("role", "user", "content", enrichedPrompt));

        // 构建工具列表
        List<Map<String, Object>> toolSpecs = tools.stream()
                .map(Tool::getFunctionSpec)
                .collect(Collectors.toList());

        // 构建请求体
        Map<String, Object> requestBodyObj = Map.of(
                "model", "qwen-max",
                "input", Map.of(
                        "messages", messages
//                        "tools", toolSpecs.isEmpty() ? null : toolSpecs,
//                        "tool_choice", "auto"
                ),
                "parameters", Map.of("result_format",
                        "message",
                        "tools", toolSpecs.isEmpty() ? null : toolSpecs,
                        "tool_choice", "auto"
                )
        );

        String apiKey = "sk-86b180d2f5254cb9b7c37af1f442baaf";
        String requestBody = mapper.writeValueAsString(requestBodyObj);

        // 【可选】脱敏：隐藏 API Key（生产环境建议）
        // String safeRequestBody = requestBody.replace(apiKey, "sk-****");
        // log.debug("➡️ 向百炼 API 发送请求: {}", safeRequestBody);
        logger.debug("➡️ 向百炼 API 发送请求: {}", requestBody);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://dashscope.aliyuncs.com/api/v1/services/aigc/text-generation/generation"))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            logger.error("❌ 调用百炼 API 时发生异常", e);
            throw new RuntimeException("AI 服务调用失败: " + e.getMessage(), e);
        }

        logger.debug("⬅️ 百炼 API 响应状态码: {}, 响应体: {}", response.statusCode(), response.body());

        // 检查 HTTP 状态码
        if (response.statusCode() != 200) {
            logger.warn("⚠️ 百炼 API 返回非200状态码: {}，响应: {}", response.statusCode(), response.body());
            throw new RuntimeException("AI 服务错误: HTTP " + response.statusCode());
        }

        // 解析 JSON 响应
        JsonNode root = mapper.readTree(response.body());

        // ✅ 关键修复：仅当存在 'code' 字段且不为 "200" 时，才视为业务错误
        if (root.has("code")) {
            String code = root.path("code").asText();
            if (!"200".equals(code)) {
                String errorMsg = root.path("message").asText("未知错误");
                logger.warn("⚠️ 百炼 API 业务错误 - code: {}, message: {}, full response: {}", code, errorMsg, response.body());
                throw new RuntimeException("AI 业务错误: " + errorMsg + " (code=" + code + ")");
            }
        }


        // 正常路径：提取模型返回的消息
        JsonNode choices = root.path("output").path("choices");
        if (choices.isEmpty() || !choices.isArray() || choices.size() == 0) {
            logger.warn("⚠️ 百炼 API 返回空 choices: {}", response.body());
            throw new RuntimeException("AI 返回结果无效：choices 为空");
        }
        String requestId = root.path("request_id").asText("N/A");
        logger.debug("📋 百炼 Request ID: {}", requestId);

// 如果是错误，也带上 request_id
        if (root.has("code") && !"200".equals(root.path("code").asText())) {
            String errorMsg = root.path("message").asText("未知错误");
            logger.warn("⚠️ 百炼 API 业务错误 - request_id: {}, code: {}, message: {}",
                    requestId, root.path("code").asText(), errorMsg);
            throw new RuntimeException("AI 业务错误: " + errorMsg);
        }

        return choices.get(0).path("message");
    }



    // ===== 工具方法：将长回复拆成多条短消息（≤25字）=====
    public List<String> splitIntoShortMessages(String reply) {
        if (reply == null || reply.trim().isEmpty()) {
            return Arrays.asList("嗯...");
        }
        reply = reply.trim();

        if (reply.length() <= 25) {
            return Arrays.asList(reply);
        }

        List<String> parts = new ArrayList<>();
        String[] sentences = reply.split("(?<=[。！？；])|\\n");

        for (String sent : sentences) {
            sent = sent.trim();
            if (sent.isEmpty()) continue;

            if (sent.length() <= 25) {
                parts.add(sent);
            } else {
                String[] byComma = sent.split("(?<=[，、])");
                if (byComma.length > 1) {
                    for (String chunk : byComma) {
                        chunk = chunk.trim();
                        if (!chunk.isEmpty()) {
                            parts.add(chunk);
                        }
                    }
                } else {
                    for (int i = 0; i < sent.length(); i += 20) {
                        parts.add(sent.substring(i, Math.min(i + 20, sent.length())));
                    }
                }
            }
        }

        final int MAX_PARTS = 5;
        if (parts.size() > MAX_PARTS) {
            // 可选：在最后一段加省略号，表示还有内容
            List<String> limited = new ArrayList<>(parts.subList(0, MAX_PARTS - 1));
            String last = parts.get(MAX_PARTS - 1).trim();
            if (!last.endsWith("…") && !last.endsWith("...")) {
                last += "…";
            }
            limited.add(last);
            return limited;
        }
        return parts;
    }

    // ===== 主动插话逻辑 =====

    public Optional<Reaction> shouldReactToGroupMessage(String groupId, String userId, String nickname, String message, List<Long> ats) {
        if (userId.equals(String.valueOf(BOT_QQ))) return Optional.empty();

        long now = System.currentTimeMillis();
        String fullUserId = groupId + "_" + userId;
        Long botQQ =356289140L;
        // ✅ 优先处理追问（不受安静性格影响）
        logger.debug(" candyBear: 尝试处理主动回复，用户 {}，群 {}，消息：{}，At：{}", userId, groupId, message, ats);
        UserThread thread = userThreads.get(fullUserId);
        logger.debug(" 正在检查是否在追问处理时间内");
        if (thread != null && now - thread.lastInteraction < 120_000) {
            logger.debug("检查完毕，处于追问时间内");// 2分钟内
            logger.debug(" candyBear: 触发追问，用户 {}，群 {}，消息：{}", userId, groupId, message);
            if(ats == null || ats.isEmpty()  || ats.contains(botQQ)) {
                if (isFollowUpMessage(message)) {
                    if (canReact(groupId)) {
                        recordReaction(groupId);
                        String prompt = "你之前说：“" + thread.lastBotReply + "”\n对方现在说：“" + message + "”\n请用一句自然的话回应。";
                        logger.debug(" candyBear: 触发追问，用户 {}，群 {}，消息：{}", userId, groupId, message);
                        return Optional.of(Reaction.withAI(prompt));
                    }
                }
            }
        }

        // === 以下才是真正的“主动插话”，受性格和概率控制 ===
        BehaviorAnalyzer.BehaviorAdvice advice = behaviorAnalyzer.getAdvice(groupId);
        double effectiveProbability = advice.adjustedProbability;
        logger.debug(" candyBear: 获取行为建议，用户 {}，群 {}，建议点数：{}", userId, groupId, effectiveProbability);
        if (0.15 > effectiveProbability) {
            logger.debug(" candyBear: 不满足概率要求，用户 {}，群 {}，概率：{}", userId, groupId, effectiveProbability);
            return Optional.empty();
        }

        Map<String, Object> personality = aiDatabaseService.getCandyBearPersonality();
        Map<String, Object> activeReply = (Map<String, Object>) personality.get("activeReply");
        double baseProbability = (double) activeReply.get("baseProbability");
        logger.debug(" candyBear: 获取性格参数，用户 {}，群 {}，参数：{}", userId, groupId, baseProbability);
        if (0.5 > baseProbability) {
            logger.debug(" candyBear: 不满足性格要求，用户 {}，群 {}，性格参数：{}", userId, groupId, baseProbability);
            return Optional.empty();
        }

        // 规则：话题兴趣匹配
        if (aiDatabaseService.shouldJoinTopic(message, groupId)) {
            logger.debug(" candyBear: 满足话题兴趣要求，用户 {}，群 {}，消息：{}", userId, groupId, message);
            if (canReact(groupId)) {
                logger.debug(" candyBear: 触发主动回复，用户 {}，群 {}，消息：{}", userId, groupId, message);
                recordReaction(groupId);
                aiDatabaseService.logActiveReplyDecision(groupId, userId, message, "reply", "topic_interest", "参与感兴趣话题");
                String prompt = "群友说：“" + message + "”\n作为糖果熊，请用一句简短文艺的话自然回应。";
                return Optional.of(Reaction.withAI(prompt));
            }
            logger.debug(" candyBear: 不满足主动回复条件，用户 {}，群 {}，消息：{}", userId, groupId, message);
        }
        logger.debug(" candyBear: 不满足话题兴趣要求，用户 {}，群 {}，消息：{}", userId, groupId, message);

        // 规则：评论 AI 历史发言
        Deque<ContextEvent> events = groupContexts.get(groupId);
        if (events != null && !events.isEmpty()) {
            Optional<ContextEvent> lastAi = events.stream()
                    .filter(e -> "ai_reply".equals(e.type))
                    .reduce((first, second) -> second);

            if (lastAi.isPresent() && now - lastAi.get().timestamp < 180_000) {
                if (isResponseToAIMessage(message, lastAi.get().content)) {
                    if (canReact(groupId)) {
                        recordReaction(groupId);
                        String prompt = "你之前说：“" + lastAi.get().content + "”\n另一个群友评论：“" + message + "”\n请友好地回应。";
                        return Optional.of(Reaction.withAI(prompt));
                    }
                }
            }
        }

        // 被动触发（红包、音乐等）
        Optional<String> passive = checkPassiveReactions(groupId, message);
        if (passive.isPresent() && canReact(groupId)) {
            recordReaction(groupId);
            return Optional.of(Reaction.direct(passive.get()));
        }

        // 简单提及“糖果熊”
        if (message.contains("糖果熊") &&
                !isFollowUpMessage(message) &&
                !message.contains("？") && !message.contains("?") &&
                message.length() <= 15) {
            if (canReact(groupId)) {
                recordReaction(groupId);
                return Optional.of(Reaction.direct("我在呢，只是在发呆～"));
            }
        }

        return Optional.empty();
    }

    // ===== 记录方法 =====

    public void recordUserInteraction(String groupId, String userId, String fullBotReply) {
        String key = groupId + "_" + userId;
        userThreads.put(key, new UserThread(System.currentTimeMillis(), fullBotReply));
    }

    public void recordGroupContext(String groupId, String userId, String nick, String msg, String type) {
        groupContexts.computeIfAbsent(groupId, k -> new ConcurrentLinkedDeque<>())
                .addLast(new ContextEvent(System.currentTimeMillis(), type, msg, userId, nick));

        Deque<ContextEvent> deque = groupContexts.get(groupId);
        if (deque != null) {
            deque.removeIf(e -> System.currentTimeMillis() - e.timestamp > 300_000);
        }
    }

    // ===== 辅助判断 =====

    private boolean isFollowUpMessage(String msg) {
        if (msg == null || msg.trim().isEmpty()) {
            return false;
        }

        String text = msg.trim();
        int len = text.length();

        if (len > 60) {
            return false;
        }

        String lower = text.toLowerCase();

        // 1. 明确疑问句
        if (text.contains("？") || text.contains("?")) {
            return true;
        }

        // 2. 常见疑问/追问关键词
        String[] questionKeywords = {
                "为什么", "怎么会", "怎么", "为何", "咋", "啥", "什么", "谁",
                "呢", "吗", "嘛", "么", "吧", "是不是", "对不对", "行不行",
                "然后", "接着", "再", "继续", "后来", "下一步",
                "你觉得", "你认为", "你说", "你刚", "你之前", "你刚刚",
                "我能不能", "我可以", "能不能", "可不可以","给我"
        };

        for (String kw : questionKeywords) {
            if (lower.contains(kw)) {
                return true;
            }
        }

        // 3. 以代词开头的短交互句
        if ((text.startsWith("你") || text.startsWith("我") || text.startsWith("我们")) && len <= 20) {
            if (lower.contains("觉得") || lower.contains("认为") ||
                    lower.contains("喜欢") || lower.contains("知道") ||
                    lower.contains("记得") || lower.contains("想") ||
                    lower.contains("在") || lower.contains("是") ||
                    lower.endsWith("呢") || lower.endsWith("啊") || lower.endsWith("呀")) {
                return true;
            }
        }

        // 4. 简短情绪/确认性语气词
        if (text.matches("(?i)^(嗯+|哦+|啊+|呃+|额+|诶+|好+|行+|对+|哈哈+|嘻嘻+|嘿嘿+|呜+|唉+)[~～!！?？]*$")) {
            return true;
        }

        // 5. 特殊模式：反问或省略主语的追问
        if ((lower.startsWith("那") || lower.startsWith("所以") || lower.startsWith("不过")) && len <= 25) {
            return true;
        }

        // 6. 极简追问：单字/双字疑问
        if (len <= 2 && (text.equals("呢") || text.equals("啊") || text.equals("哦") || text.equals("？"))) {
            return true;
        }
        if (lower.contains("你") && (
                lower.contains("擅长") ||
                        lower.contains("会") ||
                        lower.contains("能") ||
                        lower.contains("喜欢") ||
                        lower.contains("性格") ||
                        lower.contains("是什么") ||
                        lower.contains("介绍一下") ||
                        lower.contains("说说")
        )) {
            return true;
        }

        return false;
    }

    private boolean hasRecentBotActivity(String groupId) {
        Deque<ContextEvent> events = groupContexts.get(groupId);
        if (events == null) return false;
        long now = System.currentTimeMillis();
        return events.stream().anyMatch(e -> now - e.timestamp < 120_000);
    }

    // ✅ 修复：移除宽松兜底条件，仅保留明确意图
    private boolean isResponseToAIMessage(String userMsg, String aiMsg) {
        if (userMsg.length() > 50) return false;
        String lower = userMsg.toLowerCase();
        return lower.contains("不对") || lower.contains("错") ||
                lower.contains("为什么") || lower.contains("怎么") ||
                lower.contains("接着") || lower.contains("继续") ||
                lower.contains("同意") || lower.contains("觉得") ||
                lower.contains("你说") || lower.contains("刚刚") ||
                lower.contains("回应") || lower.contains("回复") ||
                (lower.contains("你") && userMsg.length() <= 20);
    }

    private Optional<String> checkPassiveReactions(String groupId, String message) {
        String lower = message.toLowerCase();
        if (message.contains("[CQ:redbag")) {

            return Optional.of("诶？有红包？手慢无啊...");
        }
        if (message.contains("[CQ:music") || lower.contains("网易云") || lower.contains("music.163")) {
            return Optional.of("这首歌我也听过，挺不错的～");
        }
//        if (message.contains("糖果熊") && !message.contains("[CQ:at,qq=" + BOT_QQ + "]")) {
//            return Optional.of("我在呢，只是在发呆～");
//        }

        // 冷场检测
        Deque<ContextEvent> recent = groupContexts.get(groupId);
        if (recent != null && recent.size() >= 3) {
            List<ContextEvent> list = new ArrayList<>(recent);
            boolean allShort = list.stream().skip(list.size() - 3)
                    .allMatch(e -> e.content.length() < 8);
            if (allShort && !message.contains("@")) {
                if (new Random().nextInt(100) < 3) {

                    return Optional.of("你们聊啥呢？突然安静了...");
                }
            }
        }

        return Optional.empty();
    }

    // ✅ 修复：将上限从 20 改为 2（更合理）
    private boolean canReact(String groupId) {
        List<Long> history = groupReactionHistory.computeIfAbsent(groupId, k -> new ArrayList<>());
        history.removeIf(ts -> System.currentTimeMillis() - ts > 300_000); // 5分钟窗口
        return history.size() < 10; // 每5分钟最多2次主动插话
    }

    private void recordReaction(String groupId) {
        groupReactionHistory.computeIfAbsent(groupId, k -> new ArrayList<>())
                .add(System.currentTimeMillis());
    }

    private List<String> extractTopics(String text) {
        List<String> topics = new ArrayList<>();
        String lower = text.toLowerCase();

        if (lower.contains("诗") || lower.contains("文学") || lower.contains("小说") || lower.contains("书")) {
            topics.add("literature");
        }
        if (lower.contains("音乐") || lower.contains("歌") || lower.contains("曲") || lower.contains("网易云")) {
            topics.add("music");
        }
        if (lower.contains("艺术") || lower.contains("画") || lower.contains("展览")) {
            topics.add("art");
        }
        if (lower.contains("电影") || lower.contains("剧") || lower.contains("影视")) {
            topics.add("film");
        }
        if (lower.contains("哲学") || lower.contains("思考") || lower.contains("人生")) {
            topics.add("philosophy");
        }

        return topics.isEmpty() ? Arrays.asList("general") : topics;
    }
    // ===== 生成追问/评论回复 =====

//    private String generateFollowUp(String groupId, String userId, String lastReply, String currentMsg) {
//        String prompt = "你之前说：“" + lastReply + "”\n对方现在说：“" + currentMsg + "”\n请用一句自然的话回应。";
//        return generate("group_" + groupId + "_" + userId, userId, prompt, groupId);
//    }
//
//    private String generateResponseToComment(String groupId, String userId, String comment, String aiMsg) {
//        String prompt = "你之前说：“" + aiMsg + "”\n另一个群友评论：“" + comment + "”\n请友好地回应。";
//        return generate("group_" + groupId + "_" + userId, userId, prompt, groupId);
//    }

    // ===== 群消息记录 =====
    public void addGroupMessage(String groupId, String message) {
        recordGroupContext(groupId, "unknown", "someone", message, "user_message");
    }
    public static class Reaction {
        public final String text;      // 直接回复的文本
        public final boolean needsAI;  // 是否需要调用 generate
        public final String prompt;    // 如果 needsAI=true，这是 prompt

        private Reaction(String text, boolean needsAI, String prompt) {
            this.text = text;
            this.needsAI = needsAI;
            this.prompt = prompt;
        }

        public static Reaction direct(String text) {
            return new Reaction(text, false, null);
        }

        public static Reaction withAI(String prompt) {
            return new Reaction(null, true, prompt);
        }
    }
}