package com.start.service;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.start.config.BotConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
        import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.Optional;

public class BaiLianService {

    private static final Logger logger = LoggerFactory.getLogger(BaiLianService.class);
    private static final long BOT_QQ = BotConfig.getBotQq();

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

    // === 新增：对话线程追踪 ===
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

    // 调用 AI（同步）
    public String generate(String sessionId, String userId, String userPrompt, String groupId) {
        try {
            // 构建上下文
            List<Message> history = sessions.computeIfAbsent(sessionId, k -> new ArrayList<>());

            // 清理过期上下文（可选）
            if (lastClearTime.containsKey(sessionId)) {
                history.clear();
                lastClearTime.remove(sessionId);
            }

            // 添加用户消息
            history.add(new Message("user", userPrompt));

            // === 构造 messages 列表（符合百炼 API 规范）===
            List<Map<String, String>> messages = new ArrayList<>();

            // 系统消息（人格设定）
            messages.add(Map.of(
                    "role", "system",
                    "content", "你叫糖果熊，是一个温柔、安静、有点文艺的女孩，说话简洁自然，像真实人类，不用【】符号，不自称小熊。"
            ));

            // 只保留最近 6 条历史（防超长）
            int start = Math.max(0, history.size() - 6);
            for (int i = start; i < history.size(); i++) {
                Message msg = history.get(i);
                String role = "user".equals(msg.role) ? "user" : "assistant";
                messages.add(Map.of("role", role, "content", msg.content));
            }

            // 调用百炼 API
            String url = "https://dashscope.aliyuncs.com/api/v1/services/aigc/text-generation/generation";
            String apiKey = "sk-86b180d2f5254cb9b7c37af1f442baaf"; // ⚠️ 建议从配置读取

            // 构造请求体（使用 ObjectMapper 避免手动拼 JSON）
            Map<String, Object> requestBodyObj = Map.of(
                    "model", "qwen-max",
                    "input", Map.of("messages", messages),
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

            // === 安全解析响应 ===
            if (response.statusCode() != 200) {
                logger.warn("百炼 API HTTP 错误 {}: {}", response.statusCode(), response.body());
                throw new RuntimeException("AI 服务暂时不可用");
            }

            JsonNode root = mapper.readTree(response.body());

            // 检查业务错误（如 InvalidApiKey）
            if (root.has("code") && !"200".equals(root.path("code").asText())) {
                String errorMsg = root.path("message").asText("未知错误");
                logger.warn("百炼 API 业务错误: code={}, message={}", root.path("code").asText(), errorMsg);
                throw new RuntimeException("AI 服务错误: " + errorMsg);
            }

            // 检查 output.choices 是否存在且非空
            JsonNode choices = root.path("output").path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                logger.warn("百炼 API 返回结果中缺少 choices，响应: {}", response.body());
                throw new RuntimeException("AI 未返回有效回复");
            }

            JsonNode firstChoice = choices.get(0);
            if (firstChoice == null || !firstChoice.has("message")) {
                logger.warn("choice[0] 格式异常，响应: {}", response.body());
                throw new RuntimeException("AI 回复格式错误");
            }

            String reply = firstChoice.path("message").path("content").asText().trim();
            reply = reply.replaceAll("【.*?】", "").trim(); // 清理多余符号

            // 保存 AI 回复到历史
            history.add(new Message("assistant", reply));

            // 记录到上下文系统（用于主动插话）
            if (groupId != null) {
                recordUserInteraction(groupId, userId, reply);
                recordGroupContext(groupId, userId, "糖果熊", reply, "ai_reply");
            }

            return reply.isEmpty() ? "嗯..." : reply;

        } catch (Exception e) {
            logger.error("AI 调用失败", e);
            return "抱歉，刚才走神了...";
        }
    }

    // ===== 主动插话逻辑 =====

    public Optional<String> shouldReactToGroupMessage(String groupId, String userId, String nickname, String message) {
        if (userId.equals(String.valueOf(BOT_QQ))) return Optional.empty();

        long now = System.currentTimeMillis();
        String fullUserId = groupId + "_" + userId;

        // 规则1: 当前用户最近与 AI 有交互（追问）
        UserThread thread = userThreads.get(fullUserId);
        if (thread != null && now - thread.lastInteraction < 120_000) { // 2分钟内
            if (isFollowUpMessage(message)) {
                if (canReact(groupId)) {
                    recordReaction(groupId);
                    return Optional.of(generateFollowUp(groupId, userId, thread.lastBotReply, message));
                }
            }
        }

        // 规则2: 消息提及“糖果熊”或含“你”且近期有 AI 活动
        if (message.contains("糖果熊") ||
                (message.contains("你") && hasRecentBotActivity(groupId))) {
            if (canReact(groupId)) {
                recordReaction(groupId);
                return Optional.of("我在呢～");
            }
        }

        // 规则3: 其他用户评论 AI 的历史发言
        Deque<ContextEvent> events = groupContexts.get(groupId);
        if (events != null && !events.isEmpty()) {
            Optional<ContextEvent> lastAi = events.stream()
                    .filter(e -> "ai_reply".equals(e.type))
                    .reduce((first, second) -> second); // 最新

            if (lastAi.isPresent() && now - lastAi.get().timestamp < 180_000) { // 3分钟内
                if (isResponseToAIMessage(message, lastAi.get().content)) {
                    if (canReact(groupId)) {
                        recordReaction(groupId);
                        return Optional.of(generateResponseToComment(groupId, userId, message, lastAi.get().content));
                    }
                }
            }
        }

        // 规则4: 被动触发（红包、音乐等）
        return checkPassiveReactions(groupId, message);
    }

    // ===== 记录方法（由 AIHandler 调用）=====

    public void recordUserInteraction(String groupId, String userId, String botReply) {
        String key = groupId + "_" + userId;
        userThreads.put(key, new UserThread(System.currentTimeMillis(), botReply));
    }

    public void recordGroupContext(String groupId, String userId, String nick, String msg, String type) {
        groupContexts.computeIfAbsent(groupId, k -> new ConcurrentLinkedDeque<>())
                .addLast(new ContextEvent(System.currentTimeMillis(), type, msg, userId, nick));

        // 清理 5 分钟前的事件
        Deque<ContextEvent> deque = groupContexts.get(groupId);
        if (deque != null) {
            deque.removeIf(e -> System.currentTimeMillis() - e.timestamp > 300_000);
        }
    }

    // ===== 辅助判断 =====

    private boolean isFollowUpMessage(String msg) {
        if (msg.length() > 60) return false;
        String lower = msg.toLowerCase();
        return lower.contains("？") || lower.contains("?") ||
                lower.contains("呢") || lower.contains("然后") ||
                lower.contains("接着") || lower.contains("为什么") ||
                lower.contains("怎么") || lower.contains("再") ||
                msg.matches("嗯+|哦+|啊+|好+|哈哈+");
    }

    private boolean hasRecentBotActivity(String groupId) {
        Deque<ContextEvent> events = groupContexts.get(groupId);
        if (events == null) return false;
        long now = System.currentTimeMillis();
        return events.stream().anyMatch(e -> now - e.timestamp < 120_000);
    }

    private boolean isResponseToAIMessage(String userMsg, String aiMsg) {
        if (userMsg.length() > 50) return false;
        String lower = userMsg.toLowerCase();
        return lower.contains("不对") || lower.contains("错") ||
                lower.contains("为什么") || lower.contains("怎么") ||
                lower.contains("接着") || lower.contains("继续") ||
                lower.contains("同意") || lower.contains("觉得") ||
                lower.contains("你说") || lower.contains("刚刚") ||
                (aiMsg.length() > 10 && userMsg.length() < 30);
    }

    private Optional<String> checkPassiveReactions(String groupId, String message) {
        if (!canReact(groupId)) return Optional.empty();

        String lower = message.toLowerCase();
        if (message.contains("[CQ:redbag")) {
            recordReaction(groupId);
            return Optional.of("诶？有红包？手慢无啊...");
        }
        if (message.contains("[CQ:music") || lower.contains("网易云") || lower.contains("music.163")) {
            recordReaction(groupId);
            return Optional.of("这首歌我也听过，挺不错的～");
        }
        if (message.contains("糖果熊") && !message.contains("[CQ:at,qq=" + BOT_QQ + "]")) {
            recordReaction(groupId);
            return Optional.of("我在呢，只是在发呆～");
        }

        // 冷场检测
        Deque<ContextEvent> recent = groupContexts.get(groupId);
        if (recent != null && recent.size() >= 3) {
            List<ContextEvent> list = new ArrayList<>(recent);
            boolean allShort = list.stream().skip(list.size() - 3)
                    .allMatch(e -> e.content.length() < 8);
            if (allShort && !message.contains("@")) {
                if (new Random().nextInt(100) < 3) {
                    recordReaction(groupId);
                    return Optional.of("你们聊啥呢？突然安静了...");
                }
            }
        }

        return Optional.empty();
    }

    private boolean canReact(String groupId) {
        List<Long> history = groupReactionHistory.computeIfAbsent(groupId, k -> new ArrayList<>());
        history.removeIf(ts -> System.currentTimeMillis() - ts > 300_000); // 5分钟
        return history.size() < 2;
    }

    private void recordReaction(String groupId) {
        groupReactionHistory.computeIfAbsent(groupId, k -> new ArrayList<>())
                .add(System.currentTimeMillis());
    }

    // ===== 生成追问/评论回复 =====

    private String generateFollowUp(String groupId, String userId, String lastReply, String currentMsg) {
        String prompt = "你之前说：“" + lastReply + "”\n对方现在说：“" + currentMsg + "”\n请用一句自然的话回应。";
        return generate("group_" + groupId + "_" + userId, userId, prompt, groupId);
    }

    private String generateResponseToComment(String groupId, String userId, String comment, String aiMsg) {
        String prompt = "你之前说：“" + aiMsg + "”\n另一个群友评论：“" + comment + "”\n请友好地回应。";
        return generate("group_" + groupId + "_" + userId, userId, prompt, groupId);
    }

    // ===== 群消息记录（供上下文使用）=====
    public void addGroupMessage(String groupId, String message) {
        recordGroupContext(groupId, "unknown", "someone", message, "user_message");
    }
}