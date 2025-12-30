package com.start.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.start.Main;

/**
 * 消息处理器接口
 */
public interface MessageHandler {
    /**
     * 判断当前消息是否由该 Handler 处理
     */
    boolean match(JsonNode message);

    /**
     * 执行具体逻辑
     */
    void handle(JsonNode message, Main bot);
}