package com.start.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.start.service.MerchantApiService.MerchantData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * 远行商人数据缓存与订阅数据库操作。
 */
public class MerchantRepository extends BaseRepository {

    private static final Logger logger = LoggerFactory.getLogger(MerchantRepository.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public void initTables() {
        executeUpdate("""
            CREATE TABLE IF NOT EXISTS merchant_cache (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                round_id VARCHAR(50) NOT NULL,
                round_number INT DEFAULT 0,
                activity_name VARCHAR(100) DEFAULT '远行商人',
                product_count INT DEFAULT 0,
                product_names TEXT,
                raw_json LONGTEXT,
                fetch_time BIGINT NOT NULL,
                UNIQUE KEY uk_round_id (round_id),
                INDEX idx_fetch_time (fetch_time)
            )
            """);
        executeUpdate("""
            CREATE TABLE IF NOT EXISTS merchant_subscription (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                group_id BIGINT NOT NULL DEFAULT 0,
                user_id BIGINT NOT NULL,
                keywords TEXT,
                match_all TINYINT DEFAULT 0,
                notify_type VARCHAR(20) DEFAULT 'at',
                enabled TINYINT DEFAULT 1,
                created_at BIGINT DEFAULT 0,
                updated_at BIGINT DEFAULT 0,
                UNIQUE KEY uk_group_user (group_id, user_id)
            )
            """);
        logger.info("✅ 远行商人数据库表已就绪");
    }

    // === 缓存操作 ===

    public MerchantData getCachedData(String roundId) {
        var result = executeQuerySingle(
            "SELECT raw_json FROM merchant_cache WHERE round_id = ?", rs -> {
                try { return rs.getString("raw_json"); } catch (SQLException e) { return null; }
            }, roundId);
        if (result.isSuccess() && result.getData() != null) {
            try {
                return MAPPER.readValue(result.getData(), MerchantData.class);
            } catch (JsonProcessingException e) {
                logger.warn("解析缓存JSON失败: {}", e.getMessage());
            }
        }
        return null;
    }

    public void saveCache(String roundId, int roundNumber, String activityName,
                          int productCount, String productNames, MerchantData data) {
        try {
            String json = MAPPER.writeValueAsString(data);
            long now = System.currentTimeMillis();
            executeUpdate(
                "INSERT INTO merchant_cache (round_id, round_number, activity_name, product_count, product_names, raw_json, fetch_time) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE product_count=VALUES(product_count), product_names=VALUES(product_names), raw_json=VALUES(raw_json), fetch_time=VALUES(fetch_time)",
                roundId, roundNumber, activityName, productCount, productNames, json, now);
        } catch (JsonProcessingException e) {
            logger.error("序列化缓存数据失败", e);
        }
    }

    /** 获取缓存的拉取时间（毫秒时间戳），没有则返回0 */
    public long getCacheFetchTime(String roundId) {
        var result = executeQuerySingle(
            "SELECT fetch_time FROM merchant_cache WHERE round_id = ?",
            rs -> { try { return rs.getLong("fetch_time"); } catch (SQLException e) { return 0L; } }, roundId);
        return result.isSuccess() && result.getData() != null ? result.getData() : 0L;
    }

    /** 缓存是否过期（超过 maxAgeMs 毫秒） */
    public boolean isCacheStale(String roundId, long maxAgeMs) {
        long fetchTime = getCacheFetchTime(roundId);
        return fetchTime == 0 || System.currentTimeMillis() - fetchTime > maxAgeMs;
    }

    public List<String> getCachedProductNames(String roundId) {
        var result = executeQuerySingle(
            "SELECT product_names FROM merchant_cache WHERE round_id = ?", rs -> {
                try { return rs.getString("product_names"); } catch (SQLException e) { return ""; }
            }, roundId);
        if (result.isSuccess() && result.getData() != null && !result.getData().isEmpty()) {
            return List.of(result.getData().split(","));
        }
        return List.of();
    }

    // === 订阅操作 ===

    public static class Subscription {
        public long id;
        public long groupId;
        public long userId;
        public String keywords;    // comma-separated, empty = match all
        public boolean matchAll;
        public String notifyType;  // "at" or "pm" (private message)
        public boolean enabled;
    }

    public void upsertSubscription(long groupId, long userId, String keywords, boolean matchAll, String notifyType) {
        long now = System.currentTimeMillis();
        executeUpdate(
            "INSERT INTO merchant_subscription (group_id, user_id, keywords, match_all, notify_type, enabled, created_at, updated_at) " +
            "VALUES (?, ?, ?, ?, ?, 1, ?, ?) ON DUPLICATE KEY UPDATE keywords=VALUES(keywords), match_all=VALUES(match_all), notify_type=VALUES(notify_type), enabled=1, updated_at=VALUES(updated_at)",
            groupId, userId, keywords, matchAll ? 1 : 0, notifyType, now, now);
    }

    public void deleteSubscription(long groupId, long userId) {
        executeUpdate("DELETE FROM merchant_subscription WHERE group_id = ? AND user_id = ?", groupId, userId);
    }

    public Subscription getSubscription(long groupId, long userId) {
        var result = executeQuerySingle(
            "SELECT id, group_id, user_id, keywords, match_all, notify_type, enabled FROM merchant_subscription WHERE group_id = ? AND user_id = ?",
            this::mapSubscription, groupId, userId);
        return result.isSuccess() ? result.getData() : null;
    }

    public List<Subscription> getEnabledSubscriptions(long groupId) {
        var result = executeQuery(
            "SELECT id, group_id, user_id, keywords, match_all, notify_type, enabled FROM merchant_subscription WHERE group_id = ? AND enabled = 1",
            this::mapSubscription, groupId);
        return result.isSuccess() ? result.getData() : List.of();
    }

    public List<Subscription> getAllEnabledSubscriptions() {
        var result = executeQuery(
            "SELECT id, group_id, user_id, keywords, match_all, notify_type, enabled FROM merchant_subscription WHERE enabled = 1",
            this::mapSubscription);
        return result.isSuccess() ? result.getData() : List.of();
    }

    private Subscription mapSubscription(ResultSet rs) {
        try {
            Subscription s = new Subscription();
            s.id = rs.getLong("id");
            s.groupId = rs.getLong("group_id");
            s.userId = rs.getLong("user_id");
            s.keywords = rs.getString("keywords");
            s.matchAll = rs.getInt("match_all") == 1;
            s.notifyType = rs.getString("notify_type");
            s.enabled = rs.getInt("enabled") == 1;
            return s;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    // === 历史查询 ===

    public List<String> getRecentHighValueRounds(int limit) {
        var result = executeQuery(
            "SELECT round_id, product_names, fetch_time FROM merchant_cache ORDER BY fetch_time DESC LIMIT ?",
            rs -> {
                try {
                    return rs.getString("round_id") + "|" + rs.getString("product_names") + "|" + rs.getLong("fetch_time");
                } catch (SQLException e) { return ""; }
            }, limit);
        return result.isSuccess() ? result.getData() : List.of();
    }
}
