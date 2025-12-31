package com.start.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.start.Main;
import com.start.config.BotConfig;
import com.start.service.BaiLianService;
import com.start.util.MessageUtil;
public class AIHandler implements MessageHandler {
    private final BaiLianService aiService = new BaiLianService();

    @Override
    public boolean match(JsonNode msg) {
        String messageType = msg.path("message_type").asText();
        long botQq = BotConfig.getBotQq();

        if ("private".equals(messageType)) {
            // 私聊：只要不是纯命令（如菜单、help），就交给 AI
            String raw = msg.path("raw_message").asText().trim();
            if (raw.isEmpty()) return false;

            // 可选：排除某些系统命令（比如你有 !menu）
            if (raw.startsWith("!") && !raw.startsWith("!ai ") && !raw.startsWith("！ai ")) {
                return false; // 不是 !ai 就不处理
            }

            return true; // 其他所有私聊消息都走 AI
        } else if ("group".equals(messageType)) {
            // 群聊：必须显式触发
            String raw = msg.path("raw_message").asText().trim();
            if (raw.startsWith("!ai ") || raw.startsWith("！ai ")) {
                return true;
            }
            // 使用你已有的工具类判断是否 @ 了机器人
            return MessageUtil.isAt(msg.path("message"), botQq);
        }

        return false;
    }

    @Override
    public void handle(JsonNode msg, Main bot) {
        String raw = msg.path("raw_message").asText();
        long selfId = msg.path("self_id").asLong();
        String prompt;

        if (raw.startsWith("!ai ")) {
            prompt = raw.substring(4).trim();
        } else {
            // 移除 @部分，兼容多个空格
            prompt = raw.replace("@" + selfId, "").replaceAll("\\s+", " ").trim();
        }

        if (prompt.isEmpty()) {
            bot.sendReply(msg, "请告诉我你想问什么～");
            return;
        }

        // 异步调用 AI（避免阻塞 WebSocket 线程）
        new Thread(() -> {
            bot.sendReply(msg, "🤔 正在思考...");
            String reply = aiService.generate(prompt);
            bot.sendReply(msg, reply);
        }).start();
    }

    private boolean isAtMe(JsonNode msg) {
        long selfId = msg.path("self_id").asLong();
        return msg.path("raw_message").asText().contains("@" + selfId);
    }
}