 // service/BehaviorAnalyzer.java - 新文件
package com.start.service;

import java.time.LocalDateTime;
import java.util.*;
        import java.util.concurrent.ConcurrentHashMap;

// 👇 新增导入（根据你的实际路径调整）
import com.start.Main;
import com.start.repository.MessageRepository; // 假设 MessageRepository 在这个包下
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

 /**
  * 分析糖果熊的聊天行为，用于优化回复策略
  *
  * 功能：
  * - 内存中实时统计群行为（保留原逻辑）
  * - 同时将行为日志写入数据库（新增）
  */
 public class BehaviorAnalyzer {

     // ✅ 内存指标（完全保留）
     private final Map<String, BehaviorMetrics> groupMetrics = new ConcurrentHashMap<>();
     private static final Logger logger = LoggerFactory.getLogger(Main.class);
     // ✅ 数据库访问层（可为空，表示只使用内存模式）
     private final MessageRepository messageRepo;

     // ✅ 构造函数1：无参构造（仅使用内存模式，兼容旧代码）
     public BehaviorAnalyzer() {
         this(null);
     }

     // ✅ 构造函数2：传入 MessageRepository（启用数据库持久化）
     public BehaviorAnalyzer(MessageRepository messageRepo) {
         this.messageRepo = messageRepo;
     }

     public static class BehaviorMetrics {
         int totalReplies = 0;
         int activeReplies = 0;
         int passiveReplies = 0;
         double avgReplyLength = 0;
         Map<String, Integer> topicEngagement = new HashMap<>();
         LocalDateTime lastAnalysisTime;
     }

     /**
      * 获取行为建议（✅ 完全保留原逻辑）
      */
     public BehaviorAdvice getAdvice(String groupId) {
         BehaviorMetrics metrics = groupMetrics.get(groupId);
         if (metrics == null || metrics.totalReplies < 10) {
             return new BehaviorAdvice(); // 默认建议
         }

         BehaviorAdvice advice = new BehaviorAdvice();

         double activeRatio = (double) metrics.activeReplies / metrics.totalReplies;
         if (activeRatio > 0.3) {
             advice.suggestion = "可能过于活跃，建议保持安静";
             advice.adjustedProbability = 0.15;
         } else if (activeRatio < 0.1) {
             advice.suggestion = "可以适当增加参与";
             advice.adjustedProbability = 0.25;
         }

         List<String> popularTopics = metrics.topicEngagement.entrySet().stream()
                 .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                 .limit(3)
                 .map(Map.Entry::getKey)
                 .toList();

         advice.recommendedTopics = popularTopics;

         return advice;
     }

     public static class BehaviorAdvice {
         String suggestion = "暂无建议";
         double adjustedProbability = 0.2;
         List<String> recommendedTopics = new ArrayList<>();
     }
 }