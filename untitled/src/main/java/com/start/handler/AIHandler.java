package com.start.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.start.Main;
import com.start.config.BotConfig;
import com.start.service.BaiLianService;
import com.start.util.MessageUtil;

import java.util.Optional;

 public class AIHandler implements MessageHandler {

     private final BaiLianService aiService = new BaiLianService();

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
             return true; // 所有群消息都进入 handle
         }
         return false;
     }

     @Override
     public void handle(JsonNode msg, Main bot) {
         long selfId = msg.path("self_id").asLong();
         long userId = msg.path("user_id").asLong();
         String messageType = msg.path("message_type").asText();
         long groupId = msg.path("group_id").asLong();

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

         // 群聊：先记录消息
         aiService.addGroupMessage(String.valueOf(groupId), senderNick + ": " + plainText);

         // 明确触发
         if (isExplicitTrigger(msg, rawMessage)) {
             handleExplicitAIRequest(bot, msg, userId, groupId, rawMessage, plainText);
             return;
         }

         // 主动插话判断
         Optional<String> reaction = aiService.shouldReactToGroupMessage(
                 String.valueOf(groupId),
                 String.valueOf(userId),
                 senderNick,
                 plainText
         );

         if (reaction.isPresent()) {
             bot.sendGroupReply(groupId, reaction.get());
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
             bot.sendReply(originalMsg, "🤔 稍等...");
             String reply = aiService.generate(sessionId, userId, prompt, groupId);
             bot.sendReply(originalMsg, reply);

             // ✅ 关键：记录这次 AI 回复到上下文系统
             if (groupId != null) {
                 String senderNick = originalMsg.path("sender").path("card").asText();
                 if (senderNick.isEmpty()) senderNick = originalMsg.path("sender").path("nickname").asText();
                 aiService.recordUserInteraction(groupId, userId, reply);
                 aiService.recordGroupContext(groupId, userId, senderNick, reply, "ai_reply");
             }
         }).start();
     }
 }