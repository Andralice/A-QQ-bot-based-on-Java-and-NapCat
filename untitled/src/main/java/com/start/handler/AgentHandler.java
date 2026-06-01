package com.start.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.start.service.AgentService;
import com.start.Main;
import com.start.service.GroupSerialExecutor;
import com.start.util.MessageUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 智能助手处理器 —— 触发词"请帮我"
 */
public class AgentHandler implements MessageHandler {

    private final AgentService agentService;
    private final GroupSerialExecutor groupExecutor;
    private static final Logger logger = LoggerFactory.getLogger(AgentHandler.class);

    public AgentHandler(AgentService agentService, GroupSerialExecutor groupExecutor) {
        this.agentService = agentService;
        this.groupExecutor = groupExecutor;
    }

    @Override
    public boolean match(JsonNode message) {
        String raw = message.path("raw_message").asText().toLowerCase();
        return raw.contains("请帮我");
    }

    @Override
    public void handle(JsonNode message, Main bot) {
        logger.debug("触发 Agent：{}", message);
        String input = message.path("raw_message").asText();
        String userId = String.valueOf(message.path("user_id").asLong());
        String groupId = message.has("group_id") ? String.valueOf(message.path("group_id").asLong()) : null;
        String cleanMessage = MessageUtil.extractPlainText(input);

        groupExecutor.execute(groupId, () -> {
            try {
                String reply = agentService.process(cleanMessage, userId, groupId);
                bot.sendReply(message, reply);
            } catch (Exception e) {
                bot.sendReply(message, "处理请求时出错了，请稍后再试～");
                logger.error("AgentHandler 执行失败", e);
            }
        });
    }
}
