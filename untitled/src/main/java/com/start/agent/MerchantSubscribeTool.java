package com.start.agent;

import com.start.repository.MerchantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * 远行商人订阅管理工具，供 AI Agent 调用。
 * AI 根据用户意图决定关键词、通知方式和操作类型后调用此工具写入数据库。
 * 当用户信息不足时，AI 应主动询问而非编造参数值。
 */
public class MerchantSubscribeTool implements Tool {

    private static final Logger logger = LoggerFactory.getLogger(MerchantSubscribeTool.class);

    private final MerchantRepository repo;

    public MerchantSubscribeTool(MerchantRepository repo) {
        this.repo = repo;
    }

    @Override public String getName() { return "lokowang_merchant_subscribe"; }

    @Override
    public String getDescription() {
        return "管理远行商人订阅提醒。当用户说「有国王球提醒我」「刷出棱镜球私聊我」「取消远行商人提醒」等意图时调用。" +
               "注意：如果用户没明确说私聊还是@，默认用 at。" +
               "如果用户没明确指定商品名，默认订阅「棱镜球,炫彩蛋,国王球」（高价值商品），并询问用户是否还要添加其他。不要编造用户没说过的商品名。";
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of("type", "object", "properties", Map.of(
            "action", Map.of("type", "string", "description", "subscribe（订阅）或 unsubscribe（取消订阅）", "enum", List.of("subscribe", "unsubscribe")),
            "group_id", Map.of("type", "number", "description", "群号，私聊时传 0"),
            "user_id", Map.of("type", "number", "description", "用户 QQ 号"),
            "keywords", Map.of("type", "string", "description", "关注的关键词，逗号分隔。空字符串表示「全部商品」。用户未指定时默认「棱镜球,炫彩蛋,国王球」"),
            "notify_type", Map.of("type", "string", "description", "通知方式：at（群内@）或 pm（私聊）。用户没说则默认 at", "enum", List.of("at", "pm"))
        ), "required", List.of("action", "group_id", "user_id"));
    }

    @Override
    public String execute(Map<String, Object> args) {
        String action = String.valueOf(args.getOrDefault("action", "subscribe"));
        long groupId = ((Number) args.getOrDefault("group_id", 0)).longValue();
        long userId = ((Number) args.getOrDefault("user_id", 0)).longValue();
        String keywords = String.valueOf(args.getOrDefault("keywords", ""));
        String notifyType = String.valueOf(args.getOrDefault("notify_type", "at"));
        boolean matchAll = keywords.isEmpty() || "全部".equals(keywords.trim());

        if (userId == 0) return "❌ 缺少 user_id，无法操作订阅。";

        if ("unsubscribe".equals(action)) {
            if (groupId > 0) {
                repo.deleteSubscription(groupId, userId);
                logger.info("📝 AI取消订阅: group={}, user={}", groupId, userId);
                return "✅ 已取消群 " + groupId + " 中用户 " + userId + " 的远行商人订阅。";
            } else {
                return "❌ 取消群订阅需要提供 group_id。";
            }
        }

        // subscribe
        if (groupId == 0) {
            return "❌ 订阅远行商人需要在群里使用。请告诉用户「请在群里发 订阅远行商人 来设置提醒」。";
        }

        repo.upsertSubscription(groupId, userId, keywords, matchAll, notifyType);
        String desc = matchAll ? "全部商品" : keywords;
        String method = "pm".equals(notifyType) ? "私聊通知" : "@提醒";
        logger.info("📝 AI订阅: group={}, user={}, keywords={}, matchAll={}, notify={}",
                groupId, userId, keywords, matchAll, notifyType);
        return "✅ 已为群 " + groupId + " 用户 " + userId + " 订阅「" + desc + "」（" + method + "）。";
    }
}
