package com.start.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.start.Main;

import java.util.ArrayList;
import java.util.List;

public class HandlerRegistry {
    private static final List<MessageHandler> handlers = new ArrayList<>();

    static {
        // 注册所有 Handler（顺序很重要！先匹配的先生效）
        handlers.add(new HelloHandler());
        handlers.add(new LuckHandler());

        // 后续新增功能，只需在这里 add(new XxxHandler())
    }

    public static void dispatch(JsonNode message, Main bot) {
        for (MessageHandler handler : handlers) {
            if (handler.match(message)) {
                handler.handle(message, bot);
                return; // 找到第一个匹配就执行并退出
            }
        }
        // 可选：默认回复
        // bot.sendReply(message, "😅 我还不会这个命令，输入「帮助」查看指令");
    }
}