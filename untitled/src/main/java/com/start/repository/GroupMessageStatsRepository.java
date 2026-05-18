package com.start.repository;

import com.start.config.DatabaseConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

/**
 * 群消息统计：记录每条群消息，支持按日/周/总计查询排行。
 */
public class GroupMessageStatsRepository {
    private static final Logger logger = LoggerFactory.getLogger(GroupMessageStatsRepository.class);

    /** 收到一条群消息时调用，当天计数 +1 */
    public static void recordMessage(String groupId, String userId) {
        try (Connection c = DatabaseConfig.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO group_message_stats (group_id, user_id, msg_date, message_count) " +
                     "VALUES (?, ?, CURDATE(), 1) " +
                     "ON DUPLICATE KEY UPDATE message_count = message_count + 1")) {
            ps.setString(1, groupId);
            ps.setString(2, userId);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.warn("记录消息统计失败: group={} user={}", groupId, userId, e);
        }
    }

    /** 按时间维度查询发言排行 */
    public static List<Map.Entry<String, Integer>> getMessageRank(String groupId, String period) {
        String dateFilter = switch (period) {
            case "today" -> "AND msg_date = CURDATE()";
            case "week" -> "AND msg_date >= DATE_SUB(CURDATE(), INTERVAL 7 DAY)";
            default -> ""; // total
        };

        Map<String, Integer> map = new LinkedHashMap<>();
        try (Connection c = DatabaseConfig.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT user_id, SUM(message_count) AS total FROM group_message_stats " +
                     "WHERE group_id = ? " + dateFilter +
                     " GROUP BY user_id ORDER BY total DESC LIMIT 15")) {
            ps.setString(1, groupId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    map.put(rs.getString("user_id"), rs.getInt("total"));
                }
            }
        } catch (SQLException e) {
            logger.error("查询发言排行失败", e);
        }
        return map.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .toList();
    }

    /** 获取时间描述 */
    public static String getPeriodLabel(String period) {
        return switch (period) {
            case "today" -> "今日";
            case "week" -> "本周";
            default -> "总";
        };
    }
}
