package com.start.handler;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 群 CP 追踪：记录谁 @ 了谁 / 回复了谁，生成社交关系排行。
 */
public class CPTracker {
    // groupId -> ( "userA|userB" -> count )
    private static final Map<String, Map<String, AtomicInteger>> pairMap = new ConcurrentHashMap<>();
    private static final long CLEAN_INTERVAL = 7 * 24 * 3600 * 1000L; // 7天清理
    private static long lastClean = System.currentTimeMillis();

    /** 记录一次互动（A @ B 或 A 回复 B） */
    public static void recordInteraction(String groupId, String userA, String userB) {
        if (userA.equals(userB)) return; // 忽略自己

        // 定期清理过期数据
        long now = System.currentTimeMillis();
        if (now - lastClean > CLEAN_INTERVAL) {
            pairMap.clear();
            lastClean = now;
        }

        Map<String, AtomicInteger> groupPairs = pairMap.computeIfAbsent(groupId, k -> new ConcurrentHashMap<>());
        String key = userA.compareTo(userB) < 0 ? userA + "|" + userB : userB + "|" + userA;
        groupPairs.computeIfAbsent(key, k -> new AtomicInteger(0)).incrementAndGet();
    }

    /** 获取群 CP 排行 TOP N */
    public static List<CPPair> getTopPairs(String groupId, int topN) {
        Map<String, AtomicInteger> groupPairs = pairMap.get(groupId);
        if (groupPairs == null || groupPairs.isEmpty()) return Collections.emptyList();

        return groupPairs.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue().get(), a.getValue().get()))
                .limit(topN)
                .map(e -> {
                    String[] ids = e.getKey().split("\\|");
                    return new CPPair(ids[0], ids[1], e.getValue().get());
                })
                .toList();
    }

    public record CPPair(String userA, String userB, int count) {}
}
