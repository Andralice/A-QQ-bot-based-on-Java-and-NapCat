package com.start.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.start.Main;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public class HandlerRegistry {
    private static final List<MessageHandler> handlers = new ArrayList<>();
    private static final Logger log = LoggerFactory.getLogger(HandlerRegistry.class);

    static {
        // 注册所有 Handler（顺序很重要！先匹配的先生效）
        handlers.add(new HelloHandler());
        handlers.add(new LuckHandler());
        handlers.add(new JokeHandler());
        handlers.add(new AIHandler());
        handlers.add(new SanjiaoHandler());
        handlers.add(new DailyProfessionHandler());
        handlers.add(new DailyCpHandler());
        // 后续新增功能，只需在这里 add(new XxxHandler())
    }

    public static void dispatch(JsonNode message, Main bot) {
        for (MessageHandler handler : handlers) {
            if (handler.match(message)) {
                handler.handle(message, bot);
                return; // 找到第一个匹配就执行并退出
//            }else {
//                log.debug("未找到匹配的handle");
            }
        }
        // 可选：默认回复
//         bot.sendReply(message, "😅 我还不会这个命令，输入「帮助」查看指令");
    }
}