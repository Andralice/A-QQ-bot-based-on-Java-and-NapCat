package com.start.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.start.Main;
import com.start.config.BotConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class DailyCpHandler implements MessageHandler {
    private static final Logger logger = LoggerFactory.getLogger(DailyCpHandler.class);

    // 触发关键词
    private static final Set<String> TRIGGERS = Set.of("抽取cp", "今日cp", "我的cp", "抽cp");

    // 每个群的当日 CP 状态：group_id → CpState
    private final Map<String, CpState> groupCpStates = new ConcurrentHashMap<>();

    @Override
    public boolean match(JsonNode message) {
        // 仅处理群消息
        if (!"group".equals(message.path("message_type").asText())) {
            return false;
        }
        String rawMsg = message.path("raw_message").asText().trim();

        // 精确匹配：消息必须完全等于某个 trigger（忽略首尾空格）
        return TRIGGERS.contains(rawMsg);
    }

    @Override
    public void handle(JsonNode message, Main bot) {
        String groupIdStr = message.get("group_id").asText();
        long groupId = Long.parseLong(groupIdStr);
        long userId = message.get("user_id").asLong();
        String userStr = String.valueOf(userId);
        String today = LocalDate.now().toString();

        // 获取或创建该群的当日状态
        CpState state = groupCpStates.computeIfAbsent(groupIdStr, k -> new CpState(today));

        // 跨天重置
        if (!state.date.equals(today)) {
            synchronized (state) {
                if (!state.date.equals(today)) {
                    state.reset(today);
                }
            }
        }

        // ✅ 异步获取群成员（带缓存）
        bot.getOneBotWsService().getGroupMemberQqListAsync(groupId)
                .thenAccept(allMembers -> {
                    String reply;

                    if (allMembers == null || allMembers.isEmpty()) {
                        reply = "😅 无法获取群成员列表，请稍后再试～";
                    } else {
                        synchronized (state) {
                            // 情况1: 用户已经是某人的 CP（被抽到）
                            if (state.cpPair.containsKey(userStr)) {
                                String partner = state.cpPair.get(userStr);
                                reply = String.format(
                                        "💖 %s，你是 [%s] 的今日 CP！\n快去和 TA 说说话吧～",
                                        BotConfig.getAt(userId),
                                        BotConfig.getAt(Long.parseLong(partner))
                                );
                            }
                            // 情况2: 用户已主动抽过（防御性检查）
                            else if (state.pairedUsers.contains(userStr)) {
                                Optional<Map.Entry<String, String>> entry = state.cpPair.entrySet().stream()
                                        .filter(e -> e.getValue().equals(userStr))
                                        .findFirst();
                                if (entry.isPresent()) {
                                    reply = String.format(
                                            "💖 %s，你是 [%s] 的今日 CP！",
                                            BotConfig.getAt(userId),
                                            BotConfig.getAt(Long.parseLong(entry.get().getKey()))
                                    );
                                } else {
                                    reply = "🤔 状态异常，请稍后再试。";
                                }
                            }
                            // 情况3: 首次抽取
                            else {
                                // 转为字符串 Set，排除自己和已配对者
                                Set<String> allMemberStrs = allMembers.stream()
                                        .map(String::valueOf)
                                        .collect(Collectors.toSet());

                                allMemberStrs.remove(userStr); // 不能抽自己
                                allMemberStrs.removeAll(state.pairedUsers); // 排除已配对的人

                                if (allMemberStrs.isEmpty()) {
                                    reply = "💔 抱歉，今天所有小伙伴都已有 CP 了！";
                                } else {
                                    // 随机选一个
                                    List<String> available = new ArrayList<>(allMemberStrs);
                                    String partner = available.get(new Random().nextInt(available.size()));

                                    // 建立双向绑定
                                    state.cpPair.put(userStr, partner);
                                    state.cpPair.put(partner, userStr);
                                    state.pairedUsers.add(userStr);
                                    state.pairedUsers.add(partner);

                                    reply = String.format(
                                            "💘 %s，你今天的 CP 是 → %s\n祝你们甜甜蜜蜜！",
                                            BotConfig.getAt(userId),
                                            BotConfig.getAt(Long.parseLong(partner))
                                    );
                                }
                            }
                        }
                    }

                    // 发送回复（在异步回调中）
                    bot.sendGroupReply(groupId, reply);
                    logger.info("💞 群 {} 用户 {} 查询今日 CP", groupId, userId);
                })
                .exceptionally(e -> {
                    logger.error("💥 异步获取群成员或生成 CP 时出错", e);
                    bot.sendGroupReply(groupId, "😅 抽取 CP 时发生错误，请稍后再试～");
                    return null;
                });
    }

    // 内部状态类：每个群每天一个实例
    private static class CpState {
        String date;
        Map<String, String> cpPair = new HashMap<>(); // 双向映射：A→B, B→A
        Set<String> pairedUsers = new HashSet<>();    // 所有已配对用户（用于快速排除）

        CpState(String date) {
            this.date = date;
        }

        void reset(String newDate) {
            this.date = newDate;
            this.cpPair.clear();
            this.pairedUsers.clear();
        }
    }
}