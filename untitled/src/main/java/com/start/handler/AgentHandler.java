package com.start.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.start.service.AgentService;
import com.start.Main; // ← 替换为你的 Main 类全路径
import com.start.service.BaiLianService;
import com.start.util.MessageUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AgentHandler implements MessageHandler {

    private final AgentService agentService;
    private static final Logger logger = LoggerFactory.getLogger(AgentHandler.class);

    public AgentHandler(AgentService agentService) {
        this.agentService = agentService;
    }

    @Override
    public boolean match(JsonNode message) {
        String raw = message.path("raw_message").asText().toLowerCase();
        return raw.contains("请帮我") ;
    }

    @Override
    public void handle(JsonNode message, Main bot) {
        logger.debug("🤖 触发 Agent：{}", message);
        try {
            String input = message.path("raw_message").asText();
            String userId = String.valueOf(message.path("user_id").asLong());
            String groupId = message.has("group_id") ? String.valueOf(message.path("group_id").asLong()) : null;
            // ⭐ 调用独立的 Agent 服务（无糖果熊人设）
            String cleanMessage = MessageUtil.extractPlainText(input);
            String reply = agentService.process(cleanMessage, userId, groupId);

            bot.sendReply(message, reply);

        } catch (Exception e) {
            String errorMsg = "处理请求时出错了，请稍后再试～";
            bot.sendReply(message, errorMsg);
            logger.error("AgentHandler 执行失败", e);
        }
    }
}