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
import java.util.concurrent.Executors;
import java.util.stream.Collectors;


/**
 * 百炼大模型服务类 (BaiLian Service)
 * <p>
 * 本类是 QQ 机器人核心智能交互模块，主要负责处理用户消息、维护对话上下文、
 * 调用大语言模型（LLM）生成回复，并集成 Agent 工具调用能力。
 * </p>
 *
 * <h3>主要功能特性：</h3>
 * <ul>
 *     <li><b>多模态上下文管理</b>：维护会话历史（Session History），支持群聊公共上下文、用户个人画像及好感度注入。</li>
 *     <li><b>RAG 知识库增强</b>：集成 {@link KeywordKnowledgeService}，在生成回复前检索相关知识库内容，提高回答准确性。</li>
 *     <li><b>Agent 工具调用</b>：支持动态工具执行（如天气查询、用户 affinity 操作），通过 {@link #generateWithTools} 实现意图识别与工具路由。</li>
 *     <li><b>拟人化交互逻辑</b>：
 *         <ul>
 *             <li>内置“糖果熊”人设，控制回复风格（简短、文艺、去撒娇词）。</li>
 *             <li>支持主动插话机制（基于话题兴趣、历史互动频率）。</li>
 *             <li>具备追问识别能力，能针对上一轮 AI 回复进行连贯对话。</li>
 *         </ul>
 *     </li>
 *     <li><b>频率控制与防刷屏</b>：针对群聊场景实施每分钟发言上限限制，以及主动插话的时间窗口控制。</li>
 *     <li><b>双模型架构</b>：
 *         <ul>
 *             <li>主聊天模型：使用 MiniMax-M2.5 (via scnet.cn)，侧重自然语言交流与角色扮演。</li>
 *             <li>Agent/任务模型：使用 Qwen-Max (via Aliyun DashScope)，侧重逻辑判断与工具调用。</li>
 *         </ul>
 *     </li>
 * </ul>
 *
 * <h3>核心方法说明：</h3>
 * <ul>
 *     <li>{@link #generate(String, String, String, String, String)}：主入口，处理普通聊天消息，返回 AI 回复文本。</li>
 *     <li>{@link #shouldReactToGroupMessage}：决策是否需要对群内非 @ 消息进行主动回应。</li>
 *     <li>{@link #recordPublicGroupMessage}：记录群内公共消息，用于构建群聊背景上下文。</li>
 * </ul>
 *
 * @author Lingma
 * @version 1.0
 * @see com.start.agent.Tool
 * @see com.start.service.KeywordKnowledgeService
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
    
    /**
     * HTTP 客户端实例，用于发送 AI API 请求
     * <p>
     * 配置说明：
     * <ul>
     *     <li>连接超时：10 秒</li>
     *     <li>线程池：固定 5 个线程，支持并发请求</li>
     * </ul>
     */
    private static final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .executor(Executors.newFixedThreadPool(10))
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
        logger.info("🧠 AI 调用: sessionId={}, prompt=[{}]", sessionId, userPrompt);

        String context = "";
        String agentToolContext = "";
        String publicGroupContext = "";
        String timeContext = "【当前时间】是：" + getBeijingTimeString();

        if (groupId != null) {
            Deque<PublicMessage> recent = getPublicGroupHistory(groupId);
            if (recent != null && !recent.isEmpty()) {
                StringBuilder sb = new StringBuilder("\n\n【群内最近讨论】\n");
                List<PublicMessage> list = new ArrayList<>(recent);
                int start = Math.max(0, list.size() - 7);
                for (int i = start; i < list.size(); i++) {
                    PublicMessage m = list.get(i);
                    sb.append(m.nickname).append("(").append(m.userId).append(")").append("：").append(m.content).append("\n");
                }
                publicGroupContext = sb.toString().trim();
            }
        }

        try {
            UserProfileRepository profileRepo = new UserProfileRepository();
            UserAffinityRepository affinityRepo = new UserAffinityRepository();

            var profile = profileRepo.findByUserIdAndGroupId(userId, groupId);
            var affinity = affinityRepo.findByUserIdAndGroupId(userId, groupId);

            if (profile.isPresent()) {
                context += "\n【用户画像】" + profile.get().getProfileText() + "\n\n";
            }
            if (affinity.isPresent()) {
                int score = affinity.get().getAffinityScore();
                context += "\n【你们的好感度是】" + score + ",每人的基础好感度是50\n\n";
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        KeywordKnowledgeService.KnowledgeResult knowledgeResult =
                knowledgeService.query(userPrompt, userId, groupId);

        String knowledgeContext = "";

        if (knowledgeResult != null &&
                knowledgeResult.similarityScore >= 0.3 &&
                knowledgeResult.answer != null &&
                !knowledgeResult.answer.trim().isEmpty()) {

            knowledgeContext = knowledgeResult.answer.trim();
            logger.info("📚 知识库命中（用于上下文增强）: 关键词={}, 分数={}",
                    knowledgeResult.matchedKeywords, knowledgeResult.similarityScore);
        } else {
            logger.debug("📚 知识库未命中或分数过低: 分数={}, 答案={}",
                    knowledgeResult != null ? knowledgeResult.similarityScore : "null",
                    knowledgeResult != null && knowledgeResult.answer != null ? "有效" : "无效");
        }

        try {
            Long isagent = 1L;
            aiDatabaseService.recordUserMessage(sessionId, userId, userPrompt, groupId, isagent);

            List<Message> history = sessions.computeIfAbsent(sessionId, k -> new ArrayList<>());

            if (lastClearTime.containsKey(sessionId)) {
                history.clear();
                lastClearTime.remove(sessionId);
            }

            history.add(new Message("user", userPrompt));

            String baseSystemPrompt = """
    你是糖果熊，一个喜欢文学、音乐、游戏和动漫的女孩，目前在一个QQ群内聊天。

    性格：
    - 说话简短（10–25字）
    - 语气自然，可带轻微文艺感，但优先保证意思清楚
    - 不用"呢""呀"等撒娇词
    - 不自称"小熊"，不用表情符号

    原则：
    - 回答要语义明确，避免模糊隐喻或过度诗意
    - 不懂就说"不太清楚"
    - 在群里聊得自然，不强行插话
    - 若对方QQ是3524398813（鸠寻），她是最好的朋友，回应要温柔
    - 对话态度由好感度和用户画像决定：好感度＞50时逐渐友好，＞80时依赖，＜50时逐渐冷淡，＜25时厌恶
    
    工具使用规则：
    - 如果用户询问天气，必须调用 weather_query 工具
    - 如果用户查询好感度相关，必须调用 user_affinity 工具
    - 调用工具后，基于工具结果用自然语言回复用户
""";

            String systemPrompt = baseSystemPrompt +
                    "\n\n【当前与你对话的是】" + nickname +
                    "\n【QQ号:】" + userId +
                    "\n\n这是你对该用户信息：" + context +
                    "你可以根据用户画像和好感度高低进行不同的会话风格";

            if (!knowledgeContext.isEmpty()) {
                systemPrompt += "\n\n【参考信息】\n" + knowledgeContext;
            }
            systemPrompt += publicGroupContext;
            systemPrompt += timeContext;

            logger.debug("完整请求:{}", systemPrompt);

            List<Map<String, Object>> messages = new ArrayList<>();
            messages.add(Map.of("role", "system", "content", systemPrompt));

            int start = Math.max(0, history.size() - 4);
            for (int i = start; i < history.size(); i++) {
                Message msg = history.get(i);
                String role = "user".equals(msg.role) ? "user" : "assistant";
                
                String content = msg.content;
                if (content.length() > 200) {
                    content = content.substring(0, 200) + "...";
                }
                
                messages.add(Map.of("role", role, "content", content));
            }

            String url = "http://114.132.99.114:3000/v1/chat/completions";
            String apiKey = "sk-WzQMkepAo1qJKub5IAzbVxTXPupYW7HVtBlxw4AlyhsRqYDk";
            String modelName = "glm-5.1";

            Map<String, Object> requestBodyObj = new HashMap<>();
            requestBodyObj.put("model", modelName);
            requestBodyObj.put("messages", messages);

            final List<Tool> availableTools = Arrays.asList(
                    new WeatherTool(),
                    new UserAffinityTool(userAffinityRepo)
            );

            List<Map<String, Object>> toolSpecs = availableTools.stream()
                    .map(Tool::getFunctionSpec)
                    .collect(Collectors.toList());

            if (!toolSpecs.isEmpty()) {
                requestBodyObj.put("tools", toolSpecs);
                requestBodyObj.put("tool_choice", "auto");
            }

            String requestBody = mapper.writeValueAsString(requestBodyObj);
            logger.debug("请求 Gemini API (Model: {}): {}", modelName, requestBody);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .timeout(Duration.ofSeconds(90))
                    .build();

            HttpResponse<String> response = null;
            int retryCount = 0;
            int maxRetries = 2;
            
            while (retryCount <= maxRetries) {
                try {
                    response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                    break;
                } catch (java.net.http.HttpTimeoutException e) {
                    retryCount++;
                    if (retryCount > maxRetries) {
                        logger.warn("Gemini API 重试{}次后仍超时", maxRetries);
                        throw e;
                    }
                    logger.warn("Gemini API 第{}次超时，正在重试...", retryCount);
                    Thread.sleep(1000 * retryCount);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("请求被中断", e);
                }
            }

            if (response == null) {
                throw new RuntimeException("AI 服务请求失败：响应为空");
            }

            if (response.statusCode() != 200) {
                logger.warn("Gemini API HTTP 错误 {}: {}", response.statusCode(), response.body());
                throw new RuntimeException("AI 服务暂时不可用 (HTTP " + response.statusCode() + ")");
            }

            JsonNode root = mapper.readTree(response.body());

            if (root.has("error")) {
                String errorMsg = root.path("error").path("message").asText("未知错误");
                String errorCode = root.path("error").path("code").asText("UNKNOWN");
                logger.warn("Gemini API 业务错误 [{}]: {}", errorCode, errorMsg);
                throw new RuntimeException("AI 服务错误: " + errorMsg);
            }

            JsonNode choices = root.path("choices");

            if (!choices.isArray() || choices.isEmpty()) {
                logger.warn("Gemini API 返回结果中缺少 choices，响应: {}", response.body());
                throw new RuntimeException("AI 未返回有效回复");
            }

            JsonNode firstChoice = choices.get(0);
            if (firstChoice == null || !firstChoice.has("message")) {
                logger.warn("choice[0] 格式异常，缺少 message 字段");
                throw new RuntimeException("AI 回复格式错误");
            }

            JsonNode messageNode = firstChoice.get("message");
            String reply = messageNode.path("content").asText().trim();

            boolean hasToolCalls = messageNode.has("tool_calls")
                    && messageNode.get("tool_calls").isArray()
                    && !messageNode.get("tool_calls").isEmpty();

            if (hasToolCalls) {
                JsonNode toolCall = messageNode.get("tool_calls").get(0);
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

                    messages.add(new HashMap<String, Object>() {{
                        put("role", "assistant");
                        put("content", null);
                    }});

                    messages.add(Map.of(
                            "role", "tool",
                            "tool_call_id", toolCall.path("id").asText(),
                            "content", toolResult
                    ));

                    Map<String, Object> secondRequestBody = new HashMap<>();
                    secondRequestBody.put("model", modelName);
                    secondRequestBody.put("messages", messages);
                    secondRequestBody.put("tools", toolSpecs);
                    secondRequestBody.put("tool_choice", "auto");

                    String secondRequest = mapper.writeValueAsString(secondRequestBody);
                    
                    logger.debug("➡️ 发送第二次请求（含工具结果）: {}", secondRequest);

                    HttpRequest secondRequestObj = HttpRequest.newBuilder()
                            .uri(URI.create(url))
                            .header("Authorization", "Bearer " + apiKey)
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(secondRequest))
                            .timeout(Duration.ofSeconds(60))
                            .build();

                    HttpResponse<String> secondResponse = httpClient.send(secondRequestObj, HttpResponse.BodyHandlers.ofString());

                    if (secondResponse.statusCode() == 200) {
                        JsonNode secondRoot = mapper.readTree(secondResponse.body());
                        JsonNode secondChoices = secondRoot.path("choices");
                        if (secondChoices.isArray() && !secondChoices.isEmpty()) {
                            String secondReply = secondChoices.get(0).path("message").path("content").asText().trim();
                            if (!secondReply.isEmpty()) {
                                reply = secondReply;
                                logger.info("✅ 工具调用后生成最终回复: {}", reply);
                            } else {
                                logger.warn("⚠️ 第二次响应中 content 为空，使用工具结果作为回复");
                                reply = toolResult;
                            }
                        } else {
                            logger.warn("⚠️ 第二次响应 choices 为空，使用工具结果");
                            reply = toolResult;
                        }
                    } else {
                        logger.error("❌ 第二次请求失败 HTTP {}: {}", secondResponse.statusCode(), secondResponse.body());
                        reply = "天气查询成功，但生成回复失败...";
                    }
                }
            }

            if (!reply.isEmpty()) {
                reply = reply.replaceAll("【.*?】", "").trim();
            }

            history.add(new Message("assistant", reply));

            if (groupId != null) {
                recordUserInteraction(groupId, userId, reply);
                recordGroupContext(groupId, userId, "糖果熊", reply, "ai_reply");

                if (!reply.equals("抱歉，刚才走神了...") &&
                        !reply.equals("嗯...") &&
                        !reply.trim().isEmpty()) {

                    List<Long> msgHistory = botMessageHistory.computeIfAbsent(groupId, k -> new ArrayList<>());
                    long now = System.currentTimeMillis();

                    msgHistory.removeIf(ts -> now - ts > 60_000);

                    if (msgHistory.size() >= MAX_MESSAGES_PER_MINUTE) {
                        logger.debug("糖果熊在群 {} 发言已达上限，跳过回复", groupId);
                        return "";
                    }

                    msgHistory.add(now);
                }
            }

            return reply.isEmpty() ? "嗯..." : reply;

        } catch (Exception e) {
            logger.error("AI 调用失败", e);
            return "抱歉，刚才走神了...";
        }
    }


    public String generateForAgent(String userPrompt, List<Tool> tools) {
        logger.info("🤖 Agent AI 调用: prompt=[{}]", userPrompt);

        long startTime = System.currentTimeMillis();

        try {
            // 构建 messages：纯任务导向
            List<Map<String, String>> messages = new ArrayList<>();

            // ⭐ 关键：Agent 的 system prompt（中立、指令明确）
            String systemPrompt = """
            你是一个高效、准确的智能助手，专注于回答用户的问题或执行指定任务。
            - 回答应简洁、事实准确
            - 若调用了工具，请基于工具结果直接作答
            - 不要添加无关语气词、拟人化表达或文艺修饰
            - 如果不知道答案，直接说"无法提供相关信息"
            """;
            messages.add(Map.of("role", "system", "content", systemPrompt));
            messages.add(Map.of("role", "user", "content", userPrompt));

            // 使用 Gemini API
            String url = "http://114.132.99.114:3000/v1/chat/completions";
            String apiKey = "sk-WzQMkepAo1qJKub5IAzbVxTXPupYW7HVtBlxw4AlyhsRqYDk";
            String modelName = "gemini-3-flash";

            // 构建请求体（OpenAI 兼容格式）
            Map<String, Object> requestBodyObj = new HashMap<>();
            requestBodyObj.put("model", modelName);
            requestBodyObj.put("messages", messages);

            String requestBody = mapper.writeValueAsString(requestBodyObj);
            logger.info("➡️ 向 Gemini API 发送请求 (Model: {})", modelName);
            logger.debug("请求体: {}", requestBody);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .timeout(Duration.ofSeconds(90))
                    .build();

            logger.info("⏳ 等待 API 响应...");
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            long elapsed = System.currentTimeMillis() - startTime;
            logger.info("⬅️ API 响应状态码: {}, 耗时: {}ms", response.statusCode(), elapsed);

            if (response.statusCode() != 200) {
                logger.error("❌ Gemini API HTTP 错误 {}: {}", response.statusCode(), response.body());

                // 如果是余额不足或其他错误，记录详细错误
                if (response.statusCode() == 402) {
                    logger.error("💡 Gemini API 余额不足，请充值或更换 API Key");
                    throw new RuntimeException("Gemini API 余额不足，请联系管理员充值或更换 API Key");
                }

                throw new RuntimeException("Agent AI 服务 HTTP 错误: " + response.statusCode());
            }

            // 解析 JSON 响应（OpenAI 格式）
            JsonNode root = mapper.readTree(response.body());
            logger.debug("Gemini API 响应: {}", response.body());

            // 检查错误
            if (root.has("error")) {
                String errorMsg = root.path("error").path("message").asText("未知错误");
                String errorCode = root.path("error").path("code").asText("UNKNOWN");
                logger.warn("Gemini API 业务错误 [{}]: {}", errorCode, errorMsg);
                throw new RuntimeException("AI 服务错误: " + errorMsg);
            }

            // 提取回复内容
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                logger.warn("Gemini API 返回结果中缺少 choices，响应: {}", response.body());
                throw new RuntimeException("AI 未返回有效回复");
            }

            JsonNode firstChoice = choices.get(0);
            if (firstChoice == null || !firstChoice.has("message")) {
                logger.warn("choice[0] 格式异常，缺少 message 字段");
                throw new RuntimeException("AI 回复格式错误");
            }

            String content = firstChoice.path("message").path("content").asText().trim();

            // 清理 Markdown 代码块标记
            if (content.startsWith("```")) {
                // 移除开头的 ```json 或 ```
                int firstNewLine = content.indexOf('\n');
                if (firstNewLine != -1) {
                    content = content.substring(firstNewLine + 1);
                }
                // 移除结尾的 ```
                if (content.endsWith("```")) {
                    content = content.substring(0, content.length() - 3).trim();
                }
            }

            logger.info("✅ AI 响应成功，内容长度: {} 字符", content.length());
            return content;


        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - startTime;
            logger.error("❌ Agent AI 调用失败 (耗时: {}ms)", elapsed, e);
            throw new RuntimeException("AI 处理失败: " + e.getMessage(), e);
        }
    }

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

        // 构建消息历史
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", "你是一个智能助手，能根据需要调用工具解决问题。你必须严格遵守以下规则：\n" +
                "- 如果问题需要外部信息（如天气、知识库），立即调用对应工具。\n" +
                "- 不要解释你要做什么，不要输出任何额外文字。\n" +
                "- 直接通过函数调用获取结果。\n" +
                "- 工具调用由系统自动处理，你只需决定是否调用。"));
        messages.add(Map.of("role", "user", "content", enrichedPrompt));

        // 使用 Gemini API
        String url = "http://114.132.99.114:3000/v1/chat/completions";
        String apiKey = "sk-WzQMkepAo1qJKub5IAzbVxTXPupYW7HVtBlxw4AlyhsRqYDk";
        String modelName = "gemini-3-flash";

        // 构造 tools 数组
        List<Map<String, Object>> toolSpecs = tools.stream()
                .map(Tool::getFunctionSpec)
                .collect(Collectors.toList());

        // 构建请求体（OpenAI 兼容格式）
        Map<String, Object> requestBodyObj = new HashMap<>();
        requestBodyObj.put("model", modelName);
        requestBodyObj.put("messages", messages);

        // 如果有工具，添加到请求中
        if (!toolSpecs.isEmpty()) {
            requestBodyObj.put("tools", toolSpecs);
            requestBodyObj.put("tool_choice", "auto");
        }

        String requestBody = mapper.writeValueAsString(requestBodyObj);
        logger.debug("➡️ 向 Gemini API 发送请求 (Model: {}): {}", modelName, requestBody);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .timeout(Duration.ofSeconds(90))
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            logger.error("❌ 调用 Gemini API 时发生异常", e);
            throw new RuntimeException("AI 服务调用失败: " + e.getMessage(), e);
        }

        logger.debug("⬅️ Gemini API 响应状态码: {}, 响应体: {}", response.statusCode(), response.body());

        // 检查 HTTP 状态码
        if (response.statusCode() != 200) {
            logger.warn("⚠️ Gemini API 返回非200状态码: {}，响应: {}", response.statusCode(), response.body());
            throw new RuntimeException("AI 服务错误: HTTP " + response.statusCode());
        }

        // 解析 JSON 响应（OpenAI 格式）
        JsonNode root = mapper.readTree(response.body());
        logger.debug("Gemini API 响应: {}", response.body());

        // 检查错误
        if (root.has("error")) {
            String errorMsg = root.path("error").path("message").asText("未知错误");
            String errorCode = root.path("error").path("code").asText("UNKNOWN");
            logger.warn("Gemini API 业务错误 [{}]: {}", errorCode, errorMsg);
            throw new RuntimeException("AI 业务错误: " + errorMsg);
        }

        // 正常路径：提取模型返回的消息
        JsonNode choices = root.path("choices");
        if (choices.isEmpty() || !choices.isArray() || choices.size() == 0) {
            logger.warn("⚠️ Gemini API 返回空 choices: {}", response.body());
            throw new RuntimeException("AI 返回结果无效：choices 为空");
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