package com.start.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.start.agent.Tool;
import com.start.agent.UserAffinityTool;
import com.start.agent.WeatherTool;
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
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.stream.Collectors;


/**
 * 百炼大模型服务类
 */
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
    private static final UserAffinityRepository userAffinityRepo = new UserAffinityRepository();

    // HTTP 客户端
    private static final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();
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

    public static String getBeijingTimeString() {
        // 1. 定义北京时区 (Asia/Shanghai 等同于北京时间)
        ZoneId beijingZone = ZoneId.of("Asia/Shanghai");

        // 2. 获取该时区的当前时间
        ZonedDateTime now = ZonedDateTime.now(beijingZone);

        // 3. 定义格式化器
        // yyyy年M月d日: 日期
        // EEEE: 完整的星期名称 (如：星期日)
        // HH:mm:ss: 24小时制时间
        // '北京时间': 固定文本
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern(
                "yyyy年M月d日 EEEE HH:mm:ss '北京时间'",
                Locale.CHINA // 确保星期和月份显示为中文
        );

        // 4. 返回格式化后的字符串
        return now.format(formatter);
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
    /**
     * 生成AI回复的核心方法
     *
     * @param sessionId 会话ID，用于维护对话历史
     * @param userId 用户QQ号
     * @param userPrompt 用户发送的消息内容
     * @param groupId 群组ID（若为私聊则为null）
     * @param nickname 用户昵称
     * @return AI生成的回复内容，若因限流等原因不回复则返回空字符串或兜底文本
     */

    public String generate(String sessionId, String userId, String userPrompt, String groupId, String nickname) {
        // 1. 记录调用日志，便于追踪问题和调试
        logger.info("🧠 AI 调用: sessionId={}, prompt=[{}]", sessionId, userPrompt);

        // 初始化各类上下文变量
        String context = "";                // 用户个人上下文（画像、好感度）
        String agentToolContext = "";       // Agent工具执行结果上下文
        String publicGroupContext = "";     // 群聊公共历史上下文
        String timeContext = "【当前时间】是：" + getBeijingTimeString();
        // 2. 【群聊上下文构建】如果是在群聊中，获取最近的群消息作为背景信息
        if (groupId != null) {
            // 获取该群最近的历史消息队列 (需确保 getPublicGroupHistory 已实现)
            Deque<PublicMessage> recent = getPublicGroupHistory(groupId);
            if (recent != null && !recent.isEmpty()) {
                StringBuilder sb = new StringBuilder("\n\n【群内最近讨论】\n");
                List<PublicMessage> list = new ArrayList<>(recent);

                // 策略：只取最近 4~6 条消息，避免上下文过长导致Token超限或干扰主任务
                // 计算起始索引：列表大小 - 7 (留有余地)，最小为0
                int start = Math.max(0, list.size() - 7);
                for (int i = start; i < list.size(); i++) {
                    PublicMessage m = list.get(i);
                    // 格式化为：昵称(QQ号)：内容
                    sb.append(m.nickname).append("(").append(m.userId).append(")").append("：").append(m.content).append("\n");
                }
                publicGroupContext = sb.toString().trim();
            }
        }

        // 3. 【Agent 预处理阶段】尝试判断是否需要调用工具或直接由小模型回答
        boolean shouldBypassMainModel = false; // 标记是否跳过主模型（直接使用Agent结果）
        String directReplyFromAgent = null;    // 存储Agent直接给出的回复

        // 定义当前可用的工具列表
        final List<Tool> availableTools = Arrays.asList(
                new WeatherTool(),                      // 天气查询工具
                new UserAffinityTool(userAffinityRepo)  // 用户好感度查询/操作工具
        );

        try {
            // 调用支持工具调用的生成方法 (内部可能调用一个小模型来判断意图)
            JsonNode agentResponse = generateWithTools(userPrompt, availableTools, userId, groupId);

            // 提取模型生成的文本内容
            String content = agentResponse.path("content").asText().trim();

            // 判断模型是否决定调用工具
            boolean hasToolCalls = agentResponse.has("tool_calls")
                    && agentResponse.get("tool_calls").isArray()
                    && !agentResponse.get("tool_calls").isEmpty();

            if (hasToolCalls) {
                // === 情况A：需要调用工具 ===
                // 获取第一个工具调用请求
                JsonNode toolCall = agentResponse.get("tool_calls").get(0);
                String toolName = toolCall.path("function").path("name").asText();
                String argsJson = toolCall.path("function").path("arguments").asText();

                // 在可用工具列表中查找对应的工具实例
                Tool tool = availableTools.stream()
                        .filter(t -> t.getName().equals(toolName))
                        .findFirst()
                        .orElse(null);

                if (tool != null) {
                    // 解析参数并执行工具
                    Map<String, Object> args = objectMapper.readValue(argsJson, Map.class);
                    String toolResult = tool.execute(args);
                    logger.info("🔧 主聊天中 Agent 工具 [{}] 结果: {}", toolName, toolResult);

                    // 将工具执行结果拼接到上下文中，供主模型参考（而不是直接返回给用户）
                    agentToolContext = "\n\n【工具执行结果】\n" + toolResult;
                }
                // 注意：此处设计为“增强上下文”，即使有工具结果，依然会走主模型进行润色和回复
            } else {
                // === 情况B：无需调用工具 ===
                if (!content.isEmpty()) {
                    // Agent 直接给出了明确的回答（例如：追问缺少的参数、拒绝回答等）
                    // 这类简短的功能性回复通常不需要主模型再次润色，直接标记为“绕过主模型”
                    shouldBypassMainModel = true;
                    directReplyFromAgent = content;
                }
                // 如果 content 为空且无工具调用，则继续走主模型流程
            }
        } catch (Exception e) {
            // Agent 预处理失败（如超时、解析错误），记录警告并降级，不影响主流程
            logger.warn("Agent 预处理失败，降级到主聊天", e);
        }

        // 4. 【用户个性化上下文构建】获取用户画像和好感度
        try {
            // 实例化仓库类 (建议优化为依赖注入，避免每次调用都new)
            UserProfileRepository profileRepo = new UserProfileRepository();
            UserAffinityRepository affinityRepo = new UserAffinityRepository();

            // 查询数据库
            var profile = profileRepo.findByUserIdAndGroupId(userId, groupId);
            var affinity = affinityRepo.findByUserIdAndGroupId(userId, groupId);

            // 拼接用户画像
            if (profile.isPresent()) {
                context += "\n【用户画像】" + profile.get().getProfileText() + "\n\n";
            }
            // 拼接好感度信息
            if (affinity.isPresent()) {
                int score = affinity.get().getAffinityScore();
                context += "\n【你们的好感度是】" + score + ",每人的基础好感度是50\n\n";
                // 预留逻辑：可根据分数动态调整提示词（当前被注释）
//                if (score >= 80) { context += ... }
//                else if (score <= 30) { context += ... }
            }
        } catch (Exception e) {
            // 数据库异常不影响聊天，打印堆栈后继续
            e.printStackTrace();
        }

        // 5. 【RAG 知识库检索】查询外部知识库以增强回答准确性
        // 调用知识库服务，基于用户问题、用户ID和群ID进行语义匹配
        KeywordKnowledgeService.KnowledgeResult knowledgeResult =
                knowledgeService.query(userPrompt, userId, groupId);

        String knowledgeContext = ""; // 初始化知识库上下文

        // 判定命中条件：结果非空 && 相似度>=0.3 && 答案有效
        if (knowledgeResult != null &&
                knowledgeResult.similarityScore >= 0.3 &&
                knowledgeResult.answer != null &&
                !knowledgeResult.answer.trim().isEmpty()) {

            knowledgeContext = knowledgeResult.answer.trim();
            logger.info("📚 知识库命中（用于上下文增强）: 关键词={}, 分数={}",
                    knowledgeResult.matchedKeywords, knowledgeResult.similarityScore);
        }

        // 6. 【主模型调用流程】调用百炼/通义千问 API 生成最终回复
        try {
            // 6.1 持久化用户消息到数据库
            Long isagent = 1L; // 标记来源
            aiDatabaseService.recordUserMessage(sessionId, userId, userPrompt, groupId, isagent);

            // 6.2 管理内存中的对话历史 (Session Cache)
            // sessions 是 ConcurrentHashMap，key为sessionId，value为消息列表
            List<Message> history = sessions.computeIfAbsent(sessionId, k -> new ArrayList<>());

            // 检查是否有重置标记（用于强制清空上下文）
            if (lastClearTime.containsKey(sessionId)) {
                history.clear();
                lastClearTime.remove(sessionId);
            }

            // 将当前用户消息加入历史
            history.add(new Message("user", userPrompt));

            // 6.3 构建 System Prompt (人设指令)
            // 使用 Java Text Block 提高可读性
            String baseSystemPrompt = """
你是糖果熊，一个喜欢文学、音乐、游戏和动漫的女孩，目前在一个QQ群内聊天。

性格：
- 说话简短（10–25字）
- 语气自然，可带轻微文艺感，但优先保证意思清楚
- 不用“呢”“呀”等撒娇词
- 不自称“小熊”，不用表情符号

原则：
- 回答要语义明确，避免模糊隐喻或过度诗意
- 不懂就说“不太清楚”
- 在群里聊得自然，不强行插话
- 若对方QQ是3524398813（鸠寻），她是最好的朋友，回应要温柔
- 对话态度由好感度和用户画像决定：好感度＞50时逐渐友好，＞80时依赖，＜50时逐渐冷淡，＜25时厌恶
""";

            // 组装完整的 System Prompt：基础人设 + 用户信息 + 知识库 + 工具结果 + 群聊历史
            String systemPrompt = baseSystemPrompt +
                    "\n\n【当前与你对话的是】" + nickname +
                    "\n【QQ号:】" + userId +
                    "\n\n这是你对该用户信息：" + context +
                    "你可以根据用户画像和好感度高低进行不同的会话风格";

            if (!knowledgeContext.isEmpty()) {
                systemPrompt += "\n\n【参考信息】\n" + knowledgeContext;
            }
            if (!agentToolContext.isEmpty()) {
                systemPrompt += agentToolContext;
            }
            systemPrompt += publicGroupContext;
            systemPrompt += timeContext;

            logger.debug("完整请求:{}", systemPrompt);

            // 6.4 构建符合 OpenAI 格式的消息列表
            List<Map<String, String>> messages = new ArrayList<>();
            messages.add(Map.of("role", "system", "content", systemPrompt));

            // 截取最近 6 条历史记录，防止上下文溢出
            int start = Math.max(0, history.size() - 6);
            for (int i = start; i < history.size(); i++) {
                Message msg = history.get(i);
                // 标准化 role 字段
                String role = "user".equals(msg.role) ? "user" : "assistant";
                messages.add(Map.of("role", role, "content", msg.content));
            }

            // 1. 配置 API 参数
            // 注意：OpenAI 兼容接口通常需要在 baseUrl 后追加 /chat/completions
            String url = "https://api.scnet.cn/api/llm/v1/chat/completions";
            String apiKey = "sk-ODE0LTExNzkwMTM4NTkyLTE3NzQxNjA4NzM0Mzk=";
            String modelName = "MiniMax-M2.5"; // 使用确认的模型 ID

            // 2. 构造请求体 (OpenAI 标准格式)
            Map<String, Object> requestBodyObj = new HashMap<>();
            requestBodyObj.put("model", modelName);
            requestBodyObj.put("messages", messages);

            // 可选：添加一些常用参数以优化效果 (根据 MiniMax 文档调整)
            // requestBodyObj.put("temperature", 0.7);
            // requestBodyObj.put("max_tokens", 2048);

            String requestBody = mapper.writeValueAsString(requestBodyObj);
            logger.debug("请求 MiniMax API (Model: {}): {}", modelName, requestBody);

            // 3. 发送 HTTP POST 请求
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            // 4. 处理 HTTP 状态码
            if (response.statusCode() != 200) {
                logger.warn("MiniMax API HTTP 错误 {}: {}", response.statusCode(), response.body());
                throw new RuntimeException("AI 服务暂时不可用 (HTTP " + response.statusCode() + ")");
            }

            // 5. 解析 JSON 响应
            JsonNode root = mapper.readTree(response.body());
            logger.debug("MiniMax API 响应: {}", response.body());

            // 6. 检查错误 (OpenAI 风格：成功无 error 字段，失败有 error 对象)
            if (root.has("error")) {
                String errorMsg = root.path("error").path("message").asText("未知错误");
                String errorCode = root.path("error").path("code").asText("UNKNOWN");
                logger.warn("MiniMax API 业务错误 [{}]: {}", errorCode, errorMsg);
                throw new RuntimeException("AI 服务错误: " + errorMsg);
            }

            // 7. 提取回复内容
            // 路径：root -> choices[0] -> message -> content
            // 注意：这里没有 "output" 层级，与阿里云百炼不同
            JsonNode choices = root.path("choices");

            if (!choices.isArray() || choices.isEmpty()) {
                logger.warn("MiniMax API 返回结果中缺少 choices，响应: {}", response.body());
                throw new RuntimeException("AI 未返回有效回复");
            }

            JsonNode firstChoice = choices.get(0);
            if (firstChoice == null || !firstChoice.has("message")) {
                logger.warn("choice[0] 格式异常，缺少 message 字段");
                throw new RuntimeException("AI 回复格式错误");
            }

            String reply = firstChoice.path("message").path("content").asText().trim();

            // 8. 后处理：去除可能的引用标记
            if (!reply.isEmpty()) {
                reply = reply.replaceAll("【.*?】", "").trim();
            }
            // 将AI回复存入历史，保持会话连续性
            history.add(new Message("assistant", reply));

            // 6.8 【群聊特有逻辑】频率控制与记录
            if (groupId != null) {
                // 记录交互数据（用于统计活跃度等）
                recordUserInteraction(groupId, userId, reply);
                // 更新群上下文缓存
                recordGroupContext(groupId, userId, "糖果熊", reply, "ai_reply");

                // === 频率限制 (Rate Limiting) ===
                // 过滤掉无意义的回复（避免浪费限流额度）
                if (!reply.equals("抱歉，刚才走神了...") &&
                        !reply.equals("嗯...") &&
                        !reply.trim().isEmpty()) {

                    // 获取该群过去1分钟内的发言时间戳列表
                    List<Long> msgHistory = botMessageHistory.computeIfAbsent(groupId, k -> new ArrayList<>());
                    long now = System.currentTimeMillis();

                    // 滑动窗口：移除超过60秒的记录
                    msgHistory.removeIf(ts -> now - ts > 60_000);

                    // 检查是否达到每分钟最大发言次数 (MAX_MESSAGES_PER_MINUTE 需在类中定义)
                    if (msgHistory.size() >= MAX_MESSAGES_PER_MINUTE) {
                        logger.debug("糖果熊在群 {} 发言已达上限，跳过回复", groupId);
                        return ""; // 返回空串，调用方应据此不发送消息
                    }

                    // 记录本次发言时间
                    msgHistory.add(now);
                }
            }

            // 返回最终结果，若为空则给一个默认兜底
            return reply.isEmpty() ? "嗯..." : reply;

        } catch (Exception e) {
            // 全局异常捕获，确保单个请求失败不会导致服务崩溃
            logger.error("AI 调用失败", e);
            return "抱歉，刚才走神了..."; // 友好的错误提示
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
                    "model", "qwen3-max-preview",
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
//        aiDatabaseService.recordUserMessage(sessionId, userId, userPrompt, groupId,isagent);
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
                "model", "qwen-plus",
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

        // ✅ 仅当存在 'code' 字段且不为 "200" 时，才视为业务错误
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

//

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
    // BaiLianService.java

    // 新增：存储每个群最近 N 条完整发言（含发言人）
    private final Map<String, Deque<PublicMessage>> publicGroupHistory = new ConcurrentHashMap<>();

    public static class PublicMessage {
        public final String userId;
        public final String nickname;
        public final String content;
        public final long timestamp;

        public PublicMessage(String userId, String nickname, String content) {
            this.userId = userId;
            this.nickname = nickname;
            this.content = content;
            this.timestamp = System.currentTimeMillis();
        }
    }

    // 提供方法供 AIHandler 调用
    public void recordPublicGroupMessage(String groupId, String userId, String nickname, String message) {
        if (groupId == null || message.trim().isEmpty()) return;

        // 过滤机器人自己的消息（避免重复）
        if (userId.equals(String.valueOf(BOT_QQ))) return;

        Deque<PublicMessage> history = publicGroupHistory.computeIfAbsent(groupId, k -> new ConcurrentLinkedDeque<>());

        // 清理过期消息（比如 10 分钟前的）
        long now = System.currentTimeMillis();
        history.removeIf(msg -> now - msg.timestamp > 10 * 60_000);

        // 保留最近 8 条（可配置）
        if (history.size() >= 8) {
            history.pollFirst();
        }

        history.offerLast(new PublicMessage(userId, nickname, message));
    }
    public Deque<PublicMessage> getPublicGroupHistory(String groupId) {
        return publicGroupHistory.get(groupId);
    }
}