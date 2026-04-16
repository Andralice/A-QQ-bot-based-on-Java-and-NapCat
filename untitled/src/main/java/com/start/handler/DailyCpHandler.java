package com.start.handler;


import com.fasterxml.jackson.databind.JsonNode;
import com.start.config.BotConfig;
import com.start.vision.CpResultData;
import com.start.vision.CpResultTemplate;
import com.start.vision.ImageRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.start.Main;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;


/**
 * 每日 CP功能模块
 */
public class DailyCpHandler implements MessageHandler {
    private static final Logger logger = LoggerFactory.getLogger(DailyCpHandler.class);

    // 触发关键词（精确匹配）
    private static final Set<String> TRIGGERS = Set.of("cp", "今日cp", "我的cp", "抽cp");

    // 每个群每天一个状态
    private final Map<String, CpState> groupCpStates = new ConcurrentHashMap<>();

    // 图片模板（单例）
    private final CpResultTemplate cpTemplate = new CpResultTemplate();

    @Override
    public boolean match(JsonNode message) {
        if (!"group".equals(message.path("message_type").asText())) {
            return false;
        }
        String rawMsg = message.path("raw_message").asText().trim();
        return TRIGGERS.contains(rawMsg);
    }

    @Override
    public void handle(JsonNode message, Main bot) {
        String groupIdStr = message.get("group_id").asText();
        long groupId = Long.parseLong(groupIdStr);
        long userId = message.get("user_id").asLong();
        String userStr = String.valueOf(userId);
        String today = LocalDate.now(ZoneId.of("Asia/Shanghai")).toString();

        // 获取或初始化当日状态
        CpState state = groupCpStates.computeIfAbsent(groupIdStr, k -> new CpState(today));

        // 跨天重置
        if (!state.date.equals(today)) {
            synchronized (state) {
                if (!state.date.equals(today)) {
                    state.reset(today);
                }
            }
        }

        // 异步获取群成员显示名
        bot.getOneBotWsService().getGroupMemberDisplayNamesAsync(groupId)
                .thenAccept(qqToName -> {
                    if (qqToName == null || qqToName.isEmpty()) {
                        bot.sendGroupReply(groupId, "😅 无法获取群成员列表，请稍后再试～");
                        logger.warn("群 {} 成员信息为空", groupId);
                        return;
                    }

                    Set<String> allMemberQqSet = qqToName.keySet();
                    String userDisplayName = qqToName.getOrDefault(userStr, "神秘用户");

                    synchronized (state) {
                        String partnerDisplayName;
                        String partnerQq = null;

                        // 情况1: 用户已被配对（是别人的 CP）
                        if (state.cpPair.containsKey(userStr)) {
                            partnerQq = state.cpPair.get(userStr);
                            partnerDisplayName = qqToName.getOrDefault(partnerQq, "神秘用户");
                        }
                        // 情况2: 用户已主动抽过（理论上不会走到这里，但保留防御）
                        else if (state.pairedUsers.contains(userStr)) {
                            Optional<Map.Entry<String, String>> entry = state.cpPair.entrySet().stream()
                                    .filter(e -> e.getValue().equals(userStr))
                                    .findFirst();
                            if (entry.isPresent()) {
                                partnerQq = entry.get().getKey();
                                partnerDisplayName = qqToName.getOrDefault(partnerQq, "神秘用户");
                            } else {
                                partnerDisplayName = null;
                                bot.sendGroupReply(groupId, "🤔 状态异常，请稍后再试。");
                                return;
                            }
                        }
                        // 情况3: 首次抽取
                        else {
                            Set<String> availableQqs = new HashSet<>(allMemberQqSet);
                            availableQqs.remove(userStr);               // 不能抽自己
                            availableQqs.removeAll(state.pairedUsers);   // 不能抽已配对者

                            if (availableQqs.isEmpty()) {
                                partnerDisplayName = null;
                                bot.sendGroupReply(groupId, "💔 抱歉，今天所有小伙伴都已有 CP 了！");
                                return;
                            } else {
                                List<String> availableList = new ArrayList<>(availableQqs);
                                partnerQq = availableList.get(ThreadLocalRandom.current().nextInt(availableList.size()));
                                partnerDisplayName = qqToName.getOrDefault(partnerQq, "神秘用户");

                                // 建立双向绑定
                                state.cpPair.put(userStr, partnerQq);
                                state.cpPair.put(partnerQq, userStr);
                                state.pairedUsers.add(userStr);
                                state.pairedUsers.add(partnerQq);
                            }
                        }

                        // ✅ 获取被抽中者的头像 URL
                        long partnerUserId = Long.parseLong(partnerQq);
                        bot.getOneBotWsService().getGroupMemberAvatarUrlAsync(groupId, partnerUserId)
                                .thenAccept(avatarUrl -> {
                                    CpResultData data = new CpResultData(
                                            userDisplayName,
                                            partnerDisplayName,
                                            avatarUrl // 可能为 null，模板会处理
                                    );

                                    String base64 = ImageRenderer.getInstance().<CpResultData>renderToBase64(cpTemplate, data);
                                    if (base64 != null) {
                                        bot.sendGroupReply(groupId, "[CQ:image,file=base64://" + base64 + "]");
                                    } else {
                                        String fallback = String.format("💘 %s，你今天的 CP 是 → %s\n（图片生成失败）",
                                                BotConfig.getAt(userId), partnerDisplayName);
                                        bot.sendGroupReply(groupId, fallback);
                                    }
                                    logger.info("💞 群 {} 用户 {} 抽取 CP 成功（含头像）", groupId, userId);
                                })
                                .exceptionally(e -> {
                                    logger.error("获取头像失败，使用无头像图片", e);
                                    CpResultData data = new CpResultData(userDisplayName, partnerDisplayName, null);
                                    String base64 = ImageRenderer.getInstance().<CpResultData>renderToBase64(cpTemplate, data);
                                    if (base64 != null) {
                                        bot.sendGroupReply(groupId, "[CQ:image,file=base64://" + base64 + "]");
                                    } else {
                                        bot.sendGroupReply(groupId, "💘 CP 抽取成功，但图片生成失败～");
                                    }
                                    return null;
                                });
                    }
                })
                .exceptionally(e -> {
                    logger.error("💥 异步处理 CP 抽取失败", e);
                    bot.sendGroupReply(groupId, "😅 抽取 CP 时发生错误，请稍后再试～");
                    return null;
                });
    }

    // 内部状态类：每个群每天独立
    private static class CpState {
        String date;
        Map<String, String> cpPair = new HashMap<>(); // A ↔ B（存储 QQ 字符串）
        Set<String> pairedUsers = new HashSet<>();     // 所有已配对用户

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