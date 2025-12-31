package com.start.util;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.time.Instant;

/**
 * 简单的速率限制器：每个 key 在 N 秒内只能触发一次
 */
public class RateLimiter {
    private final long windowSeconds;
    /**
     * 存储每个触发器的最后触发时间戳
     * 键为触发器标识符，值为最后一次触发的时间戳（毫秒）
     */
    private final Map<String, Long> lastTriggerTime = new ConcurrentHashMap<>();

    public RateLimiter(long windowSeconds) {
        this.windowSeconds = windowSeconds;
    }

    /**
     * 尝试触发，成功返回 true（未超频），失败返回 false（已触发过）
     */
    public boolean tryAcquire(String key) {
        long now = Instant.now().getEpochSecond();
        Long last = lastTriggerTime.get(key);
        if (last == null || now - last >= windowSeconds) {
            lastTriggerTime.put(key, now);
            return true;
        }
        return false;
    }
}