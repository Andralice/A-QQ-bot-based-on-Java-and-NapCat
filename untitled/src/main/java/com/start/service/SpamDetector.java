package com.start.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.start.Main;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

public class SpamDetector {
    private static final Logger logger = LoggerFactory.getLogger(SpamDetector.class);

    // 窗口大小：检查最近 N 条消息
    private static final int WINDOW_SIZE = 5;
    // 触发条件：相同内容出现 M 次
    private static final int MIN_REPEAT_COUNT = 3;
    // 最小消息长度：避免对过短的消息进行检测
    private static final int MIN_MESSAGE_LENGTH = 2;

    private final Map<String, Deque<MessageRecord>> groupMessages = new ConcurrentHashMap<>();
    private final Map<String, Long> lastTriggerTime = new ConcurrentHashMap<>();
    private static final long COOLDOWN_SECONDS = 5; // 冷却时间

    private final Main bot;

    public SpamDetector(Main bot) {
        this.bot = bot;
    }

    /**
     * 检查并中断连续相同的刷屏行为
     *
     * @param groupId      群ID
     * @param userId       用户ID
     * @param rawMessage   原始消息内容
     */
    public void checkAndInterrupt(String groupId, long userId, String rawMessage) {
        if (groupId == null || rawMessage == null) return;

        String content = rawMessage.trim().toLowerCase();
        if (content.length() < MIN_MESSAGE_LENGTH) return; // 过滤掉过短的消息

        Deque<MessageRecord> queue = groupMessages.computeIfAbsent(groupId, k -> new ArrayDeque<>());
        synchronized (queue) {
            queue.addLast(new MessageRecord(userId, content));
            while (queue.size() > WINDOW_SIZE) {
                queue.pollFirst();
            }

            if (isSpam(queue, content)) {
                long now = System.currentTimeMillis() / 1000;
                Long last = lastTriggerTime.get(groupId);
                if (last == null || now - last > COOLDOWN_SECONDS) {
                    lastTriggerTime.put(groupId, now);
                    String[] replies = {
                            "📢 打断施法！",
                            "🛑 禁止加一",
                            "⚠️ 检测到重复内容，律行停止！"
                    };
                    String reply = replies[new Random().nextInt(replies.length)];
                    bot.sendGroupReply(Long.parseLong(groupId), reply);
                    logger.info("🔄 群 {} 触发防刷屏（内容重复 {} 次）", groupId, MIN_REPEAT_COUNT);
                }
            }
        }
    }

    /**
     * 判断是否为刷屏行为
     *
     * @param window  消息队列
     * @param content 当前消息内容
     * @return 是否触发刷屏规则
     */
    private boolean isSpam(Deque<MessageRecord> window, String content) {
        int count = 0;
        for (MessageRecord record : window) {
            if (record.content.equals(content)) {
                count++;
            }
        }
        return count >= MIN_REPEAT_COUNT;
    }

    /**
     * 消息记录类
     */
    private static class MessageRecord {
        final long userId;
        final String content;

        MessageRecord(long userId, String content) {
            this.userId = userId;
            this.content = content;
        }
    }
}