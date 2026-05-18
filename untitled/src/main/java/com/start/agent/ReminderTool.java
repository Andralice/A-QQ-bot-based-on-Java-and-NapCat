package com.start.agent;

import com.start.service.ReminderService;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 定时提醒工具，AI 调用后在指定时间后 @ 提醒发起者。
 * 支持："30分钟后提醒我抢票" "5分钟后提醒开会" 等。
 */
public class ReminderTool implements Tool {

    @Override public String getName() { return "set_reminder"; }

    @Override
    public String getDescription() {
        return "设置定时提醒。" +
               "给自己设提醒：'X分钟后提醒我XX'→ target_user_id填自己的QQ。" +
               "给别人设私聊提醒：'X分钟后私聊粉喵告诉她XX'→ target_user_id填粉喵的QQ, mode=private。" +
               "delay 填'30分钟''1小时'等，message 填提醒内容。";
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of("type", "object",
                "properties", Map.of(
                        "delay", Map.of("type", "string", "description", "时长，如'30分钟''1小时'"),
                        "message", Map.of("type", "string", "description", "提醒内容"),
                        "user_id", Map.of("type", "string", "description", "发起者QQ（在群里@谁）"),
                        "target_user_id", Map.of("type", "string", "description", "要被提醒的人QQ，不填则提醒user_id"),
                        "mode", Map.of("type", "string", "description", "group(群提醒) 或 private(私聊提醒)"),
                        "group_id", Map.of("type", "string", "description", "群号")
                ),
                "required", Arrays.asList("delay", "message", "user_id"));
    }

    @Override
    public String execute(Map<String, Object> args) {
        String delay = (String) args.get("delay");
        String message = (String) args.get("message");
        String userId = (String) args.get("user_id");
        String targetId = (String) args.getOrDefault("target_user_id", userId);
        String mode = (String) args.getOrDefault("mode", "group");
        String groupId = (String) args.get("group_id");

        if (delay == null || message == null || userId == null) return "参数不全";

        long seconds = ReminderService.parseDelaySeconds(delay);
        if (seconds <= 0 || seconds > 86400) return "时长须在 1秒~24小时 之间";

        LocalDateTime triggerTime = LocalDateTime.now().plusSeconds(seconds);
        ReminderService rs = ReminderService.getInstance();

        try {
            long uid = Long.parseLong(userId);
            long tid = targetId != null ? Long.parseLong(targetId) : uid;

            if ("private".equals(mode) && groupId != null && !"null".equals(groupId)) {
                long gid = Long.parseLong(groupId);
                rs.remindPrivate(gid, tid, "⏰ " + message, triggerTime);
                return "已设置，" + delay + "后私聊 " + tid + "：" + message;
            } else if (groupId != null && !"null".equals(groupId) && !groupId.isEmpty()) {
                long gid = Long.parseLong(groupId);
                rs.remindAtGroup(gid, uid, "⏰ " + message, triggerTime);
                return "已设置，" + delay + "后在群里@" + userId + "：" + message;
            } else {
                rs.remindAt(uid, "⏰ " + message, triggerTime);
                return "已设置，" + delay + "后私聊提醒：" + message;
            }
        } catch (NumberFormatException e) {
            return "QQ号格式错误";
        }
    }
}
