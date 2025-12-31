package com.start;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.start.config.BotConfig;
import com.start.handler.HandlerRegistry;
import com.start.handler.MessageHandler;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.net.URI;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
public class Main extends WebSocketClient {

    private static final Logger logger = LoggerFactory.getLogger(Main.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static String wsUrl;
    private static final Set<Long> ALLOWED_GROUPS = BotConfig.getAllowedGroups();
    private static final Set<Long> ALLOWED_PRIVATE_USERS = BotConfig.getAllowedPrivateUsers();
    static {
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
            if ("message".equals(event.path("post_type").asText())) {

                String messageType = event.path("message_type").asText();
                long userId = event.path("user_id").asLong();

                boolean isAllowed = false;

                if ("group".equals(messageType)) {
                    long groupId = event.path("group_id").asLong();
                    if (BotConfig.getAllowedGroups().contains(groupId)) {
                        isAllowed = true;
                    } else {
                        logger.debug("🚫 忽略非白名单群消息 | group_id={}", groupId);
                    }
                } else if ("private".equals(messageType)) {
                    // 👇 核心逻辑：判断是否启用私聊白名单
                    if (!BotConfig.isPrivateWhitelistEnabled()) {
                        isAllowed = true; // 开关关闭 → 允许所有私聊
                        logger.debug("💬 接受私聊（白名单未启用）| user_id={}", userId);
                    } else {
                        if (BotConfig.getAllowedPrivateUsers().contains(userId)) {
                            isAllowed = true; // 开关开启 → 仅白名单
                            logger.debug("💬 接受白名单私聊 | user_id={}", userId);
                        } else {
                            logger.debug("🚫 忽略非白名单私聊 | user_id={}", userId);
                        }
                    }
                }
                // 注意：不处理 "discuss" 等其他类型

                if (isAllowed) {
                    HandlerRegistry.dispatch(event, this);
                }
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
            Main newBot = new Main(new URI(wsUrl));
            newBot.connect();
            // 等待连接关闭（保持主线程不退出）
            while (!newBot.isClosed()) {
                Thread.sleep(1000);
            }
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

    // 提供一个公共的 send 方法供 MessageHandler 调用
    public void sendReply(JsonNode msg, String reply) {
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

    public static void main(String[] args) throws Exception {
        Main bot = new Main(new URI(wsUrl));
        bot.connect();
        while (!bot.isClosed()) {
            Thread.sleep(1000);
        }
    }
}