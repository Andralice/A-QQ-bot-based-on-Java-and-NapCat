// com/start/service/OneBotWsService.java

package com.start.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.start.Main;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class OneBotWsService {
    private static final Logger logger = LoggerFactory.getLogger(OneBotWsService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Main botClient;

    // ✅ 新增：缓存结构 { groupId -> (memberList, expireTime) }
    private static final Map<Long, CachedGroupMembers> groupMemberCache = new ConcurrentHashMap<>();

    // 缓存有效期：5 分钟（单位：毫秒）
    private static final long CACHE_EXPIRE_MS = 60 * 60 * 1000;

    public OneBotWsService(Main botClient) {
        this.botClient = botClient;
    }

    // ===== 核心方法：获取群成员（带缓存）=====
    public CompletableFuture<List<Long>> getGroupMemberQqListAsync(long groupId) {
        long now = System.currentTimeMillis();

        // 1️⃣ 检查缓存是否有效
        CachedGroupMembers cached = groupMemberCache.get(groupId);
        if (cached != null && now < cached.expireTime) {
            logger.debug("✅ 使用缓存的群 {} 成员列表（{} 人）", groupId, cached.members.size());
            return CompletableFuture.completedFuture(new ArrayList<>(cached.members));
        }

        // 2️⃣ 缓存失效或不存在 → 异步加载
        logger.info("🔄 正在加载群 {} 的成员列表...", groupId);

        ObjectNode params = MAPPER.createObjectNode();
        params.put("group_id", groupId);

        return botClient.callOneBotApi("get_group_member_list", params)
                .thenApply(response -> {
                    if (response == null) {
                        logger.warn("❌ 群 {} 成员列表 API 超时或无响应", groupId);
                        return Collections.<Long>emptyList();
                    }

                    if (!"ok".equals(response.path("status").asText())) {
                        String errorMsg = response.path("msg").asText("未知错误");
                        logger.warn("❌ 群 {} 成员列表 API 错误: {}", groupId, errorMsg);
                        return Collections.<Long>emptyList();
                    }

                    JsonNode data = response.path("data");
                    if (!data.isArray()) {
                        logger.warn("❌ 群 {} 返回数据不是数组", groupId);
                        return Collections.<Long>emptyList();
                    }

                    Set<Long> members = new HashSet<>();
                    for (JsonNode member : data) {
                        long userId = member.path("user_id").asLong();
                        if (userId > 10000) { // 排除系统号、小号
                            members.add(userId);
                        }
                    }

                    // ✅ 更新缓存
                    groupMemberCache.put(groupId, new CachedGroupMembers(new ArrayList<>(members), now + CACHE_EXPIRE_MS));
                    logger.info("✅ 成功缓存群 {} 的 {} 名成员", groupId, members.size());

                    return new ArrayList<>(members);
                });
    }

    // ===== 辅助类：带过期时间的缓存 =====
    private static class CachedGroupMembers {
        final List<Long> members;
        final long expireTime;

        CachedGroupMembers(List<Long> members, long expireTime) {
            this.members = members;
            this.expireTime = expireTime;
        }
    }

    // 可选：提供同步阻塞方法（不推荐用于主线程）
    public List<Long> getGroupMemberQqList(long groupId) {
        try {
            return getGroupMemberQqListAsync(groupId).get(12, TimeUnit.SECONDS);
        } catch (Exception e) {
            logger.error("⚠️ 同步获取群成员失败", e);
            return Collections.emptyList();
        }
    }
}