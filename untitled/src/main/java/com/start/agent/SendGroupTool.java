package com.start.agent;

import com.start.Main;

import java.util.*;

/**
 * 发群消息工具。用于在私聊中让糖果熊替自己往群里传话。
 */
public class SendGroupTool implements Tool {
    private final Main bot;

    public SendGroupTool(Main bot) {
        this.bot = bot;
    }

    @Override public String getName() { return "send_group_msg"; }

    @Override
    public String getDescription() {
        return "向指定群发送消息。当有人在私聊里说'帮我在群里说XX''帮我@XX一下'时调用。" +
               "也可以在重要通知、游戏结果等场景主动发到群里。";
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of("type", "object",
                "properties", Map.of(
                        "group_id", Map.of("type", "string", "description", "目标群号"),
                        "message", Map.of("type", "string", "description", "要发送的内容，@人用[CQ:at,qq=QQ号]")
                ),
                "required", Arrays.asList("group_id", "message"));
    }

    @Override
    public String execute(Map<String, Object> args) {
        String groupId = (String) args.get("group_id");
        String message = (String) args.get("message");
        if (groupId == null || message == null) return "缺少 group_id 或 message";

        try {
            bot.sendGroupReply(Long.parseLong(groupId), message);
            return "已发送到群 " + groupId;
        } catch (Exception e) {
            return "发送失败: " + e.getMessage();
        }
    }
}
