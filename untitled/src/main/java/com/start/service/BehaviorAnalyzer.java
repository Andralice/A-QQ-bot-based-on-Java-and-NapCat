// service/BehaviorAnalyzer.java - 新文件
package com.start.service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 分析糖果熊的聊天行为，用于优化回复策略
 */
public class BehaviorAnalyzer {

    // 记录各种指标
    private final Map<String, BehaviorMetrics> groupMetrics = new ConcurrentHashMap<>();

    public static class BehaviorMetrics {
        int totalReplies = 0;
        int activeReplies = 0;
        int passiveReplies = 0;
        double avgReplyLength = 0;
        Map<String, Integer> topicEngagement = new HashMap<>();
        LocalDateTime lastAnalysisTime;
    }

    /**
     * 记录一次回复
     */
    public void recordReply(String groupId, String reply, boolean isActive,
                            List<String> topics) {
        BehaviorMetrics metrics = groupMetrics.computeIfAbsent(
                groupId, k -> new BehaviorMetrics());

        metrics.totalReplies++;
        if (isActive) metrics.activeReplies++;
        else metrics.passiveReplies++;

        // 更新平均长度
        metrics.avgReplyLength = (metrics.avgReplyLength * (metrics.totalReplies - 1)
                + reply.length()) / metrics.totalReplies;

        // 记录话题参与
        for (String topic : topics) {
            metrics.topicEngagement.put(topic,
                    metrics.topicEngagement.getOrDefault(topic, 0) + 1);
        }

        metrics.lastAnalysisTime = LocalDateTime.now();
    }

    /**
     * 获取行为建议
     */
    public BehaviorAdvice getAdvice(String groupId) {
        BehaviorMetrics metrics = groupMetrics.get(groupId);
        if (metrics == null || metrics.totalReplies < 10) {
            return new BehaviorAdvice(); // 默认建议
        }

        BehaviorAdvice advice = new BehaviorAdvice();

        // 分析活跃度
        double activeRatio = (double) metrics.activeReplies / metrics.totalReplies;
        if (activeRatio > 0.3) {
            advice.suggestion = "可能过于活跃，建议保持安静";
            advice.adjustedProbability = 0.15;
        } else if (activeRatio < 0.1) {
            advice.suggestion = "可以适当增加参与";
            advice.adjustedProbability = 0.25;
        }

        // 分析话题偏好
        List<String> popularTopics = metrics.topicEngagement.entrySet().stream()
                .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                .limit(3)
                .map(Map.Entry::getKey)
                .toList();

        advice.recommendedTopics = popularTopics;

        return advice;
    }

    public static class BehaviorAdvice {
        String suggestion;
        double adjustedProbability = 0.2;
        List<String> recommendedTopics = new ArrayList<>();
    }
}
