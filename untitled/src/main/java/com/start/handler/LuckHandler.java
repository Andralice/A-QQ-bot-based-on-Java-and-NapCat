package com.start.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.start.Main;
import com.start.config.BotConfig;
import com.start.util.MessageUtil;
import com.start.util.LuckUtil;
import com.start.util.RateLimiter;

/**
 * 幸运值
 */
public class LuckHandler implements MessageHandler {
    // 30秒内同一用户不能重复触发
    private static final RateLimiter rateLimiter = new RateLimiter(5);

    @Override
    public boolean match(JsonNode msg) {
        long botQq = BotConfig.getBotQq();
        String botName = BotConfig.getBotName();
        String plainText = MessageUtil.extractPlainText(msg.path("message"));

        if (plainText == null) {
            plainText = "";
        }
        plainText = plainText.trim();

        // 只精确匹配：消息纯文本完全等于关键词才触发
        return "幸运值".equals(plainText) || "运势".equals(plainText) ||
                "今日魔咒".equals(plainText) || "魔咒".equals(plainText);
    }

    @Override
    public void handle(JsonNode msg, Main bot) {
        long userId = msg.path("user_id").asLong();
        long groupId = msg.path("group_id").asLong(); // 群聊才有，私聊可忽略

        // 构建唯一 key：群+用户（如果是群消息），否则只用用户
        String cacheKey;
        if (msg.has("group_id")) {
            cacheKey = "luck:" + groupId + ":" + userId;
        } else {
            cacheKey = "luck:private:" + userId;
        }

        // 防刷：30秒内不重复响应
        if (!rateLimiter.tryAcquire(cacheKey)) {
            // 可选：悄悄忽略，或回复“别急，稍后再试”
            return;
        }

        int luck = LuckUtil.getDailyLuck(userId);
        var spell = LuckUtil.getDailySpell(userId);
        String Atthis =BotConfig.getAt(userId);
        String raw = msg.path("raw_message").asText().trim();
        boolean showSpell = raw.contains("魔咒") || raw.contains("宜") || raw.contains("不宜");

        String reply;
        if (luck >= 90) {
            reply =Atthis +"🌟 欧气爆棚！今天幸运值 " + luck;
        } else if (luck >= 70) {
            reply =Atthis + "😊 运气不错～今天幸运值 " + luck;
        } else if (luck >= 40) {
            reply =Atthis +"🙂 平平无奇，幸运值 " + luck;
        } else {
            reply =Atthis + "😞 今天小心，幸运值只有 " + luck;
        }

        if (showSpell) {
            reply += "\n" + spell.mood() + "\n✅ " + spell.doSpell() + " | ❌ " + spell.avoidSpell();
        }

        bot.sendReply(msg, reply);
        // 记录到 AI 上下文
        var baiLian = bot.getBaiLianService();
        if (baiLian != null && msg.has("group_id")) {
            baiLian.recordBotAction(String.valueOf(groupId), String.valueOf(userId),
                    msg.path("sender").path("nickname").asText(""), "运势查询",
                    "幸运值:" + luck + " " + spell.doSpell() + " " + spell.avoidSpell());
        }
    }
}