package com.start;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.start.config.BotConfig;

import com.start.config.DatabaseConfig;
import com.start.handler.AIHandler;
import com.start.handler.HandlerRegistry;
import com.start.service.*;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.net.URI;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

public class Main extends WebSocketClient {

    private static final Logger logger = LoggerFactory.getLogger(Main.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static String wsUrl;
    private static final Set<Long> ALLOWED_GROUPS = BotConfig.getAllowedGroups();
    private static final Set<Long> ALLOWED_PRIVATE_USERS = BotConfig.getAllowedPrivateUsers();
    private UserService userService;
    private MessageService messageService;
    private ConversationService conversationService;
    private PersonalityService personalityService;
    private AIDatabaseService aiDatabaseService;
    private BaiLianService baiLianService;
    private HandlerRegistry handlerRegistry;

    // ===== 新增：用于处理 WebSocket API 响应 =====
    private final Map<String, CompletableFuture<JsonNode>> pendingRequests = new ConcurrentHashMap<>();

    // ===== 服务实例 =====
    private SpamDetector spamDetector;

    private final OneBotWsService oneBotWsService; // 新增

    static {
        System.setProperty("dashscope.api-key", "sk-86b180d2f5254cb9b7c37af1f442baaf");
        System.out.println("DEBUG: dashscope.api-key = " +
                System.getProperty("dashscope.api-key", "NOT SET"));
        try (InputStream is = Main.class.getClassLoader().getResourceAsStream("application.properties")) {
            if (is == null) {
                throw new RuntimeException("❌ 未找到 application.properties 文件！");
            }
            Properties props = new Properties();
            props.load(is);
            wsUrl = props.getProperty("ws.url");
            if (wsUrl == null || wsUrl.trim().isEmpty()) {
                throw new RuntimeException("❌ application.properties 中缺少 ws.url 配置！");
            }
            logger.info("🔧 已加载 WebSocket 地址: {}", wsUrl);
        } catch (Exception e) {
            logger.error("❌ 初始化配置失败", e);
            throw new RuntimeException("配置加载失败", e);
        }
    }

    public Main(URI serverUri) {
        super(serverUri);
        DatabaseConfig.initConnectionPool();
        this.oneBotWsService = new OneBotWsService(this); // 初始化 WebSocket API 服务
        this.userService = new UserService();
        this.messageService = new MessageService();
        this.conversationService = new ConversationService();
        this.personalityService = new PersonalityService();
        this.aiDatabaseService = new AIDatabaseService();
        this.handlerRegistry = new HandlerRegistry();

    }

    public void init() {
        this.spamDetector = new SpamDetector(this);
        logger.info("🛡️ SpamDetector 初始化完成");
    }

    @Override
    public void onOpen(ServerHandshake handshakedata) {
        logger.info("✅ 已连接 NapCat WebSocket");
    }

    @Override
    public void onMessage(String message) {
        logger.debug("📡 原始事件: {}", message);

        try {
            JsonNode event = MAPPER.readTree(message);
            long userId1 = event.path("user_id").asLong();
            long selfId1 = event.path("self_id").asLong(); // ← 关键！OneBot 事件自带 self_id
            logger.debug("👤 user_id={}, self_id={}", userId1, selfId1);
            // ✅ 优先处理 API 响应（带 echo 字段）
            if (event.has("echo")) {
                String echo = event.get("echo").asText();
                CompletableFuture<JsonNode> future = pendingRequests.remove(echo);
                if (future != null) {
                    future.complete(event);
                    return; // 不走后续消息处理流程
                }
            }

            // 只处理 message 类型事件
            if (!"message".equals(event.path("post_type").asText())) {
                return;
            }

            String messageType = event.path("message_type").asText();
            long userId = event.path("user_id").asLong();
            boolean isAllowed = false;

            if ("group".equals(messageType)) {
                long groupId = event.path("group_id").asLong();
                if (ALLOWED_GROUPS.contains(groupId)) {
                    isAllowed = true;
                } else {
                    logger.debug("🚫 忽略非白名单群消息 | group_id={}", groupId);
                }
            } else if ("private".equals(messageType)) {
                if (!BotConfig.isPrivateWhitelistEnabled()) {
                    isAllowed = true;
                    logger.debug("💬 接受私聊（白名单未启用）| user_id={}", userId);
                } else {
                    if (ALLOWED_PRIVATE_USERS.contains(userId)) {
                        isAllowed = true;
                        logger.debug("💬 接受白名单私聊 | user_id={}", userId);
                    } else {
                        logger.debug("🚫 忽略非白名单私聊 | user_id={}", userId);
                    }
                }
            }

            if (isAllowed) {
                String rawMessage = event.path("raw_message").asText();

                if ("group".equals(messageType)) {
                    long groupId = event.path("group_id").asLong();
                    if (this.spamDetector != null) {
                        this.spamDetector.checkAndInterrupt(String.valueOf(groupId), userId, rawMessage);
                    } else {
                        logger.warn("⚠️ SpamDetector 未初始化，跳过防刷检测");
                    }
                }

                HandlerRegistry.dispatch(event, this);
            }

        } catch (Exception e) {
            logger.error("❌ 处理消息失败", e);
        }
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        logger.warn("❌ 连接断开 (code={}, remote={}), 5秒后重连...", code, remote);
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
        scheduler.schedule(this::reconnect, 5, TimeUnit.SECONDS);
    }

    public void reconnect() {
        try {
            logger.info("🔄 尝试重连...");
            this.connect();
            logger.info("✅ 重连成功");
        } catch (Exception e) {
            logger.error("⚠️ 重连失败，10秒后再次尝试...", e);
            ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
            scheduler.schedule(this::reconnect, 10, TimeUnit.SECONDS);
        }
    }

    @Override
    public void onError(Exception ex) {
        logger.error("🔥 WebSocket 发生错误", ex);
    }

    // ===== 新增：通过 WebSocket 调用 OneBot API =====
    public CompletableFuture<JsonNode> callOneBotApi(String action, JsonNode params) {
        String echo = "req_" + System.currentTimeMillis() + "_" + ThreadLocalRandom.current().nextInt(1000000);
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pendingRequests.put(echo, future);

        ObjectNode request = MAPPER.createObjectNode();
        request.put("action", action);
        request.set("params", params);
        request.put("echo", echo);

        this.send(request.toString());
        logger.debug("📤 发送 OneBot API 请求: action={}, echo={}", action, echo);

        return future.orTimeout(10, TimeUnit.SECONDS)
                .exceptionally(t -> {
                    logger.warn("⏰ OneBot API 调用失败或超时: action={}, echo={}", action, echo, t);
                    return null;
                });
    }

    // ===== 消息发送方法 =====
    public void sendReply(JsonNode msg, String reply) {
        String traceId = "send_" + System.currentTimeMillis() + "_" + ThreadLocalRandom.current().nextInt(1000);
        logger.debug("📤 [{}] 发送群聊回复: {}", traceId, reply);
        try {
            ObjectNode action = MAPPER.createObjectNode();
            String msgType = msg.path("message_type").asText();
            action.put("action", "send_" + msgType + "_msg");

            ObjectNode params = action.putObject("params");
            if ("group".equals(msgType)) {
                params.put("group_id", msg.path("group_id").asLong());
            } else {
                params.put("user_id", msg.path("user_id").asLong());
            }
            params.put("message", reply);

            this.send(action.toString());
            logger.debug("📤 已发送回复: {}", reply);
        } catch (Exception e) {
            logger.error("❌ 发送回复失败", e);
        }
    }

    public void sendPrivateReply(long userId, String reply) {
        String traceId = "send_" + System.currentTimeMillis() + "_" + ThreadLocalRandom.current().nextInt(1000);
        logger.debug("📤 [{}] 发送群聊回复: {}", traceId, reply);
        try {
            ObjectNode action = MAPPER.createObjectNode();
            action.put("action", "send_private_msg");
            ObjectNode params = action.putObject("params");
            params.put("user_id", userId);
            params.put("message", reply);
            this.send(action.toString());
            logger.debug("📤 已发送私聊回复: {}", reply);
        } catch (Exception e) {
            logger.error("❌ 发送私聊回复失败", e);
        }
    }

    public void sendGroupReply(long groupId, String reply) {
        String traceId = "send_" + System.currentTimeMillis() + "_" + ThreadLocalRandom.current().nextInt(1000);
        logger.debug("📤 [{}] 发送群聊回复: {}", traceId, reply);
        try {
            ObjectNode action = MAPPER.createObjectNode();
            action.put("action", "send_group_msg");
            ObjectNode params = action.putObject("params");
            params.put("group_id", groupId);
            params.put("message", reply);
            this.send(action.toString());
            logger.debug("📤 已发送群聊回复: {}", reply);
        } catch (Exception e) {
            logger.error("❌ 发送群聊回复失败", e);
        }
    }

    // ===== Getter =====


    public OneBotWsService getOneBotWsService() {
        return oneBotWsService;
    }

    // ===== Main 入口 =====
    public static void main(String[] args) throws Exception {
        Main bot = new Main(new URI(wsUrl));
        bot.connect();
        bot.init();
        while (!bot.isClosed()) {
            Thread.sleep(1000);
        }
    }
}