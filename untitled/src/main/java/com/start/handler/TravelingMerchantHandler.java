package com.start.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.start.Main;
import com.start.config.BotConfig;
import com.start.repository.MerchantRepository;
import com.start.repository.MerchantRepository.Subscription;
import com.start.service.MerchantApiService;
import com.start.service.MerchantApiService.MerchantData;
import com.start.service.MerchantApiService.MerchantRoundInfo;
import com.start.vision.MerchantCardRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 远行商人查询处理器。
 * 支持：查看商品、订阅提醒、取消订阅、查看订阅。
 * 数据缓存到数据库，每4小时自动刷新。
 */
public class TravelingMerchantHandler implements MessageHandler {

    private static final Logger logger = LoggerFactory.getLogger(TravelingMerchantHandler.class);
    private static final int[] REFRESH_HOURS = {8, 12, 16, 20};
    private static final int CHECK_OFFSET_MINUTES = 3;

    private final MerchantApiService apiService;
    private final MerchantRepository repo;
    private final Main bot;
    private final MerchantCardRenderer cardRenderer = new MerchantCardRenderer();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "MerchantSchedule");
        t.setDaemon(true);
        return t;
    });

    private final Set<String> highValueItems;
    private String lastCheckedRoundId = "";
    private int retryCount = 0;

    public TravelingMerchantHandler(MerchantApiService apiService, MerchantRepository repo, Main bot) {
        this.apiService = apiService;
        this.repo = repo;
        this.bot = bot;
        this.highValueItems = BotConfig.getMerchantHighValueItems();
        logger.info("✅ 远行商人处理器已初始化（缓存模式，高价值物资={}）", highValueItems);
        startScheduledCheck();
    }

    // === 消息匹配 ===

    @Override
    public boolean match(JsonNode message) {
        String text = extractText(message);
        if (text == null) return false;
        String t = text.trim();
        return t.equals("远行商人") || t.startsWith("远行商人") || t.startsWith("订阅远行商人")
                || t.equals("取消订阅远行商人") || t.equals("查看远行商人订阅");
    }

    @Override
    public void handle(JsonNode message, Main bot) {
        String text = extractText(message).trim();
        long groupId = extractGroupId(message);
        long userId = extractUserId(message);

        if (text.equals("远行商人")) {
            handleQuery(groupId, userId);
        } else if (text.startsWith("订阅远行商人")) {
            handleSubscribe(groupId, userId, text);
        } else if (text.equals("取消订阅远行商人")) {
            handleUnsubscribe(groupId, userId);
        } else if (text.equals("查看远行商人订阅")) {
            handleViewSubscriptions(groupId, userId);
        }
    }

    // === 查询 ===

    private void handleQuery(long groupId, long userId) {
        try {
            MerchantData data = apiService.fetchMerchantInfo(false);
            String base64 = cardRenderer.renderToBase64(data);
            if (base64 != null) {
                String msg = "[CQ:image,file=base64://" + base64 + "]";
                sendReply(groupId, userId, msg);
            } else {
                // 渲染失败时回落文本
                sendReply(groupId, userId, apiService.formatForReply(data));
            }
        } catch (Exception e) {
            logger.error("远行商人查询失败", e);
            sendReply(groupId, userId, "❌ 远行商人数据获取失败，请稍后重试。");
        }
    }

    // === 订阅管理 ===

    private void handleSubscribe(long groupId, long userId, String text) {
        if (groupId == 0) {
            bot.sendPrivateReply(userId, "远行商人订阅暂不支持私聊，请在群内使用。");
            return;
        }
        String args = text.substring("订阅远行商人".length()).trim();
        boolean matchAll = args.isEmpty() || args.equals("全部");
        String keywords = matchAll ? "" : args;
        repo.upsertSubscription(groupId, userId, keywords, matchAll, "at");
        String msg = matchAll ? "✅ 已订阅远行商人全部商品提醒！每轮刷新时若有货会 @ 你。"
                : "✅ 已订阅「" + keywords + "」提醒！当远行商人刷出匹配商品时会 @ 你。";
        bot.sendGroupReply(groupId, "[CQ:at,qq=" + userId + "] " + msg);
    }

    private void handleUnsubscribe(long groupId, long userId) {
        if (groupId == 0) return;
        repo.deleteSubscription(groupId, userId);
        bot.sendGroupReply(groupId, "[CQ:at,qq=" + userId + "] ✅ 已取消远行商人订阅。");
    }

    private void handleViewSubscriptions(long groupId, long userId) {
        if (groupId == 0) return;
        List<Subscription> subs = repo.getEnabledSubscriptions(groupId);
        if (subs.isEmpty()) {
            bot.sendGroupReply(groupId, "📋 当前群暂无远行商人订阅。\n发送「订阅远行商人 国王球 棱镜球」即可订阅。");
            return;
        }
        StringBuilder sb = new StringBuilder("📋 当前群远行商人订阅：\n");
        for (Subscription s : subs) {
            sb.append("· ");
            if (s.matchAll) sb.append("全部商品");
            else sb.append(s.keywords);
            sb.append(" —— QQ:").append(s.userId);
            sb.append("\n");
        }
        bot.sendGroupReply(groupId, sb.toString().trim());
    }

    // === 定时检测 ===

    private synchronized void startScheduledCheck() {
        long initialDelay = computeDelayToNextCheck();
        scheduler.scheduleAtFixedRate(() -> {
            try { scheduledCheck(); }
            catch (Exception e) { logger.error("❌ 远行商人定时检测异常", e); }
        }, initialDelay, 60, TimeUnit.SECONDS);
        logger.info("⏰ 远行商人定时检测已启动（每{}分钟后检查，目标群=所有订阅群）", CHECK_OFFSET_MINUTES);
    }

    private long computeDelayToNextCheck() {
        LocalDateTime now = LocalDateTime.now();
        LocalTime currentTime = now.toLocalTime();
        for (int hour : REFRESH_HOURS) {
            LocalTime checkTime = LocalTime.of(hour, CHECK_OFFSET_MINUTES);
            if (currentTime.isBefore(checkTime)) {
                return Duration.between(now, now.toLocalDate().atTime(checkTime)).getSeconds();
            }
        }
        LocalTime firstCheck = LocalTime.of(REFRESH_HOURS[0], CHECK_OFFSET_MINUTES);
        return Duration.between(now, now.plusDays(1).toLocalDate().atTime(firstCheck)).getSeconds();
    }

    private void scheduledCheck() {
        LocalTime now = LocalTime.now();
        boolean isCheckMinute = false;
        for (int hour : REFRESH_HOURS) {
            if (Math.abs(now.toSecondOfDay() - LocalTime.of(hour, CHECK_OFFSET_MINUTES).toSecondOfDay()) < 60) {
                isCheckMinute = true;
                break;
            }
        }
        if (!isCheckMinute && retryCount == 0) return;

        try {
            MerchantData data = apiService.fetchMerchantInfo(true); // force refresh
            if (data == null || data.products.isEmpty()) {
                if (retryCount == 0) {
                    logger.info("📦 远行商人暂无数据，2分钟后重试");
                    retryCount = 1;
                } else {
                    logger.info("📦 远行商人重试仍无数据，放弃本轮");
                    retryCount = 0;
                }
                return;
            }
            retryCount = 0;

            MerchantRoundInfo round = data.roundInfo;
            if (round == null || round.roundId.equals(lastCheckedRoundId)) return;
            lastCheckedRoundId = round.roundId;

            List<String> allNames = data.products.stream().map(p -> p.name).toList();
            List<String> highMatches = apiService.findHighValueMatches(data, highValueItems);
            notifySubscribers(data, allNames, highMatches);

        } catch (Exception e) {
            logger.error("❌ 远行商人定时检测失败", e);
        }
    }

    // === 通知订阅者 ===

    private void notifySubscribers(MerchantData data, List<String> allNames, List<String> highMatches) {
        List<Subscription> allSubs = repo.getAllEnabledSubscriptions();
        if (allSubs.isEmpty()) {
            logger.debug("📋 无远行商人订阅者，跳过通知");
            return;
        }

        for (Subscription sub : allSubs) {
            if (!sub.enabled || sub.groupId == 0) continue;
            List<String> matched;
            if (sub.matchAll) {
                if (data.products.isEmpty()) continue;
                matched = allNames;
            } else {
                List<String> keywords = List.of(sub.keywords.split("[,，]"));
                matched = data.products.stream()
                        .map(p -> p.name)
                        .filter(name -> keywords.stream().anyMatch(name::contains))
                        .distinct()
                        .toList();
                if (matched.isEmpty()) continue;
            }

            sendSubscriberNotification(sub, data, allNames, matched, highMatches);
        }
    }

    private void sendSubscriberNotification(Subscription sub, MerchantData data,
                                            List<String> allNames, List<String> matched, List<String> highMatches) {
        StringBuilder sb = new StringBuilder();
        sb.append("🔔 远行商人刷新提醒\n");
        sb.append("📍 第").append(data.roundInfo.current).append("/").append(data.roundInfo.total).append("轮\n");
        sb.append("📦 当前商品：").append(String.join("、", allNames)).append("\n");

        if (!sub.matchAll) {
            sb.append("🎯 你关注的：").append(String.join("、", matched)).append("\n");
        }
        if (!highMatches.isEmpty() && !sub.matchAll) {
            sb.append("💎 高价值物资：").append(String.join("、", highMatches)).append("\n");
        }
        sb.append("━━━━━━━━━━\n");
        sb.append("[CQ:at,qq=").append(sub.userId).append("]");

        if ("pm".equals(sub.notifyType)) {
            bot.sendPrivateReply(sub.userId, sub.groupId, sb.toString());
        } else {
            bot.sendGroupReply(sub.groupId, sb.toString());
        }
    }

    // === 辅助 ===

    private void sendReply(long groupId, long userId, String msg) {
        if (groupId != 0) bot.sendGroupReply(groupId, msg);
        else bot.sendPrivateReply(userId, msg);
    }

    private String extractText(JsonNode message) {
        try {
            if (message.has("raw_message")) return message.get("raw_message").asText();
            if (message.has("message")) {
                JsonNode msgNode = message.get("message");
                if (msgNode.isArray()) {
                    StringBuilder sb = new StringBuilder();
                    for (JsonNode node : msgNode) {
                        if ("text".equals(node.path("type").asText())) {
                            String text = node.path("data").path("text").asText();
                            if (text != null && !text.trim().isEmpty()) sb.append(text).append("\n");
                        }
                    }
                    return sb.toString().trim();
                }
            }
        } catch (Exception e) { logger.error("提取消息文本失败", e); }
        return null;
    }

    private long extractGroupId(JsonNode message) {
        try {
            if (message.has("group_id")) return message.get("group_id").asLong();
            if (message.has("sender") && message.get("sender").has("group_id"))
                return message.get("sender").get("group_id").asLong();
        } catch (Exception ignored) {}
        return 0;
    }

    private long extractUserId(JsonNode message) {
        try {
            if (message.has("user_id")) return message.get("user_id").asLong();
            if (message.has("sender") && message.get("sender").has("user_id"))
                return message.get("sender").get("user_id").asLong();
        } catch (Exception ignored) {}
        return 0;
    }

    // === 供 Tool 调用 ===

    public java.util.concurrent.CompletableFuture<String> queryMerchantSync(Main bot) {
        java.util.concurrent.CompletableFuture<String> future = new java.util.concurrent.CompletableFuture<>();
        try {
            MerchantData data = apiService.fetchMerchantInfo(false);
            future.complete(apiService.formatForReply(data));
        } catch (Exception e) {
            logger.error("远行商人同步查询失败", e);
            future.complete("⏰ 远行商人查询失败，请稍后重试。");
        }
        return future;
    }
}
