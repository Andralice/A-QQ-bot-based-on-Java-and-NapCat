package com.start.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 每群独立单线程执行器，保证同一群内的 AI 调用和游戏逻辑串行执行。
 * 私聊使用共享线程池，不做串行化。
 */
public class GroupSerialExecutor {
    private static final Logger logger = LoggerFactory.getLogger(GroupSerialExecutor.class);

    private final Map<String, ExecutorService> groupExecutors = new ConcurrentHashMap<>();
    private final ExecutorService privateChatExecutor;
    private final long defaultMaxQueueTimeMs;

    public GroupSerialExecutor(int privateThreads, long defaultMaxQueueTimeMs) {
        this.privateChatExecutor = Executors.newFixedThreadPool(privateThreads, r -> {
            Thread t = new Thread(r, "Private-AI-Worker");
            t.setDaemon(true);
            return t;
        });
        this.defaultMaxQueueTimeMs = defaultMaxQueueTimeMs;
    }

    /** 提交任务，使用默认超时 */
    public void execute(String groupId, Runnable task) {
        execute(groupId, task, defaultMaxQueueTimeMs);
    }

    /**
     * 提交任务到对应群的串行队列。超过排队时间的任务会被丢弃。
     *
     * @param groupId 群号（null 表示私聊，走共享线程池）
     * @param task    要执行的任务
     * @param maxQueueTimeMs 最大排队时间毫秒，超过则丢弃
     */
    public void execute(String groupId, Runnable task, long maxQueueTimeMs) {
        long submitTime = System.currentTimeMillis();

        Runnable wrapped = () -> {
            long waited = System.currentTimeMillis() - submitTime;
            if (waited > maxQueueTimeMs) {
                logger.debug("丢弃过期任务 group={} 排队{}ms", groupId, waited);
                return;
            }
            if (waited > 500) {
                logger.debug("任务排队{}ms group={}", waited, groupId);
            }
            task.run();
        };

        if (groupId == null) {
            privateChatExecutor.submit(wrapped);
        } else {
            ExecutorService executor = groupExecutors.computeIfAbsent(groupId,
                    k -> Executors.newSingleThreadExecutor(r -> {
                        Thread t = new Thread(r, "Group-" + k + "-Worker");
                        t.setDaemon(true);
                        return t;
                    }));
            executor.submit(wrapped);
        }
    }

    /** 关闭所有执行器 */
    public void shutdown() {
        groupExecutors.values().forEach(e -> {
            e.shutdown();
            try { e.awaitTermination(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        });
        privateChatExecutor.shutdown();
    }
}
