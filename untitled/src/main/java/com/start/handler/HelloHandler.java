package com.start.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.start.Main;
import com.start.config.BotConfig;
import com.start.util.MessageUtil;

public class HelloHandler implements MessageHandler {
    @Override
    public boolean match(JsonNode msg) {
        String text = MessageUtil.extractPlainText(msg.path("message"));
        long botQq = BotConfig.getBotQq(); // 从配置读取机器人QQ

        // 必须同时满足：1. 被 @；2. 包含“你好”
        return MessageUtil.isAt(msg.path("message"), botQq) &&
                text.contains("你好");
    }

    @Override
    public void handle(JsonNode msg, Main bot) {
        bot.sendReply(msg, "你好！我是 糖果熊~");
    }
}