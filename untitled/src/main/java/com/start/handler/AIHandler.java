package com.start.handler;


import com.fasterxml.jackson.databind.JsonNode;
import com.start.Main;
import com.start.config.BotConfig;
import com.start.service.BaiLianService;
import com.start.util.MessageUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.Random;

import static com.start.util.MessageUtil.extractAts;

public class AIHandler implements MessageHandler {

    private static final Logger log = LoggerFactory.getLogger(AIHandler.class);
    private final BaiLianService aiService = new BaiLianService();
    private final Random random = new Random();

    @Override
    public boolean match(JsonNode msg) {
        String messageType = msg.path("message_type").asText();
        if ("private".equals(messageType)) {
            String raw = msg.path("raw_message").asText().trim();
            if (raw.isEmpty()) return false;
            if (raw.startsWith("!") &&
                    !raw.startsWith("!ai ") &&
                    !raw.startsWith("！ai ") &&
                    !raw.startsWith("#ai ")) {
                return false;
            }
            return true;
        } else if ("group".equals(messageType)) {
            return true;
        }
        return false;
    }

    @Override
    public void handle(JsonNode msg, Main bot) {
        long selfId = msg.path("self_id").asLong();
        long userId = msg.path("user_id").asLong();
        String messageType = msg.path("message_type").asText();
        long groupId = msg.path("group_id").asLong();
        List<Long> ats = extractAts(msg);
        if (userId == selfId) return;

        String plainText = MessageUtil.extractPlainText(msg.path("message")).trim();
        String rawMessage = msg.path("raw_message").asText();
        String senderNick = msg.path("sender").path("card").asText();
        if (senderNick.isEmpty()) {
            senderNick = msg.path("sender").path("nickname").asText();
        }

        // 私聊
        if ("private".equals(messageType)) {
            handlePrivateMessage(bot, msg, userId, rawMessage, plainText);
            return;
        }

        // 群聊：先记录原始消息到上下文
        aiService.addGroupMessage(String.valueOf(groupId), senderNick + ": " + plainText);

        // 明确触发（#ai / !ai / @）
        if (isExplicitTrigger(msg, rawMessage)) {
            handleExplicitAIRequest(bot, msg, userId, groupId, rawMessage, plainText);
            return;
        }

        // 主动插话判断
        Optional<BaiLianService.Reaction> reaction = aiService.shouldReactToGroupMessage(
                String.valueOf(groupId),
                String.valueOf(userId),
                senderNick,
                plainText,
                ats
        );

        if (reaction.isPresent()) {
            BaiLianService.Reaction r = reaction.get();
            if (r.needsAI) {
                // 异步调用 generate
                new Thread(() -> {
                    String reply = aiService.generate("group_" + groupId + "_" + userId, String.valueOf(userId), r.prompt, String.valueOf(groupId));
                    if (!reply.trim().isEmpty() && !reply.equals("抱歉，刚才走神了...") && !reply.equals("嗯...")) {
                        sendSplitGroupReplies(bot, groupId, reply);
                        aiService.recordUserInteraction(String.valueOf(groupId), String.valueOf(userId), reply);
                        aiService.recordGroupContext(String.valueOf(groupId), String.valueOf(userId), "糖果熊", reply, "ai_reply");
                    }
                }).start();
            } else {
                sendSplitGroupReplies(bot, groupId, r.text);
            }
        }
    }

    private void handlePrivateMessage(Main bot, JsonNode msg, long userId, String rawMessage, String plainText) {
        String prompt = extractPrompt(rawMessage, plainText);
        String sessionId = "private_" + userId;

        if (isClearCommand(prompt)) {
            aiService.clearContext(sessionId);
            bot.sendReply(msg, "🧹 已清除我们的聊天记忆！");
            return;
        }

        if (prompt.isEmpty()) {
            bot.sendReply(msg, "想聊什么？直接说就好～");
            return;
        }

        replyWithAI(bot, msg, sessionId, String.valueOf(userId), prompt, null);
    }

    private void handleExplicitAIRequest(Main bot, JsonNode msg, long userId, long groupId, String rawMessage, String plainText) {
        String prompt = extractPrompt(rawMessage, plainText);
        String sessionId = "group_" + groupId + "_" + userId;

        if (isClearCommand(prompt)) {
            aiService.clearContext(sessionId);
            bot.sendReply(msg, "🧹 已清除我们的聊天记忆！");
            return;
        }

        if (prompt.isEmpty()) {
            bot.sendReply(msg, "问点什么吧～");
            return;
        }

        replyWithAI(bot, msg, sessionId, String.valueOf(userId), prompt, String.valueOf(groupId));
    }

    private boolean isExplicitTrigger(JsonNode msg, String rawMessage) {
        return rawMessage.startsWith("#ai ") ||
                rawMessage.startsWith("!ai ") ||
                rawMessage.startsWith("！ai ") ||
                MessageUtil.isAt(msg.path("message"), BotConfig.getBotQq());
    }

    private String extractPrompt(String rawMessage, String plainText) {
        if (rawMessage.startsWith("#ai ")) return rawMessage.substring(4).trim();
        if (rawMessage.startsWith("!ai ")) return rawMessage.substring(4).trim();
        if (rawMessage.startsWith("！ai ")) return rawMessage.substring(5).trim();
        return plainText;
    }

    private boolean isClearCommand(String prompt) {
        return "#clear".equals(prompt) || "!clear".equals(prompt) || "！clear".equals(prompt);
    }

    private void replyWithAI(Main bot, JsonNode originalMsg, String sessionId, String userId, String prompt, String groupId) {
        new Thread(() -> {
            // 发送“思考中”提示
//            bot.sendReply(originalMsg, "🤔 稍等...");

            // 调用 AI（内部已做频率限制）
            String reply = aiService.generate(sessionId, userId, prompt, groupId);

            if (reply == null || reply.trim().isEmpty()) {
                // 被频率限制或出错，不发后续
                return;
            }

            // ✅ 关键：拆分并发送多条（模拟真人）
            if (groupId != null) {
                long gId = Long.parseLong(groupId);
                sendSplitGroupReplies(bot, gId, reply);

                // 记录上下文（用于后续插话）
                String senderNick = originalMsg.path("sender").path("card").asText();
                if (senderNick.isEmpty()) senderNick = originalMsg.path("sender").path("nickname").asText();
                aiService.recordUserInteraction(groupId, userId, reply);
                aiService.recordGroupContext(groupId, userId, senderNick, reply, "ai_reply");
            } else {
                // 私聊：目前不分句（可选）
                bot.sendReply(originalMsg, reply);

            }
        }).start();
    }

    /**
     * ✅ 核心方法：将 AI 回复拆分为多条短消息，并逐条发送（带打字延迟）
     */
    private void sendSplitGroupReplies(Main bot, long groupId, String fullReply) {
        List<String> parts = aiService.splitIntoShortMessages(fullReply);
        for (int i = 0; i < parts.size(); i++) {
            String msg = parts.get(i).trim();
            if (msg.isEmpty()) continue;

            // 第一条快一点，后续模拟打字
            int delayMs = (i == 0) ? (random.nextInt(300) + 200) : (random.nextInt(1000) + 500);
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }

            bot.sendGroupReply(groupId, msg);
        }
    }
}