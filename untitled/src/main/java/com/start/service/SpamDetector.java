package com.start.service;

import com.start.Main;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class SpamDetector {
    private static final Logger logger = LoggerFactory.getLogger(SpamDetector.class);

    // 窗口大小：保留最近 N 条消息用于检测
    private static final int WINDOW_SIZE = 5;
    // 触发条件：末尾连续相同内容出现 M 次
    private static final int MIN_REPEAT_COUNT = 3;
    // 最小消息长度：避免对过短或无意义消息进行检测（如 "."、" "）
    private static final int MIN_MESSAGE_LENGTH = 1;

    private final Map<String, Deque<MessageRecord>> groupMessages = new ConcurrentHashMap<>();
    private final Map<String, Long> lastTriggerTime = new ConcurrentHashMap<>();
    private static final long COOLDOWN_SECONDS = 5; // 触发后冷却时间（秒）

    private final Main bot;

    public SpamDetector(Main bot) {
        this.bot = bot;
    }

    /**
     * 检查并中断连续相同的刷屏行为
     *
     * @param groupId      群ID（字符串形式）
     * @param userId       用户ID
     * @param rawMessage   原始消息内容
     */
    public void checkAndInterrupt(String groupId, long userId, String rawMessage) {
        if (groupId == null || rawMessage == null) return;

        String content = rawMessage.trim().toLowerCase();
        if (content.length() < MIN_MESSAGE_LENGTH) return;

        Deque<MessageRecord> queue = groupMessages.computeIfAbsent(groupId, k -> new ArrayDeque<>());
        synchronized (queue) {
            // 添加新消息
            queue.addLast(new MessageRecord(userId, content));
            // 保持窗口大小
            while (queue.size() > WINDOW_SIZE) {
                queue.pollFirst();
            }

            // 仅当末尾有连续 ≥ MIN_REPEAT_COUNT 条相同消息时触发
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
                    logger.info("🔄 群 {} 触发防刷屏（连续相同内容 {} 次）", groupId, MIN_REPEAT_COUNT);
                }
            }
        }
    }

    /**
     * 判断是否为连续刷屏行为（仅检查队列末尾的连续相同消息）
     *
     * @param window  消息队列（按时间顺序，最新在末尾）
     * @param content 当前消息内容（即最后一条）
     * @return 是否触发刷屏规则
     */
    private boolean isSpam(Deque<MessageRecord> window, String content) {
        int consecutiveCount = 0;
        // 从最新消息开始向前遍历（descendingIterator = 从尾到头）
        for (Iterator<MessageRecord> it = new ArrayDeque<>(window).descendingIterator(); it.hasNext(); ) {
            MessageRecord record = it.next();
            if (record.content.equals(content)) {
                consecutiveCount++;
                if (consecutiveCount >= MIN_REPEAT_COUNT) {
                    return true; // 达到阈值，立即触发
                }
            } else {
                break; // 连续性被中断，停止计数
            }
        }
        return false;
    }

    /**
     * 消息记录内部类
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