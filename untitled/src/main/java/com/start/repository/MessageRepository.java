// repository/MessageRepository.java
// repository/MessageRepository.java
package com.start.repository;

import com.start.model.ChatMessage;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;

public class MessageRepository extends BaseRepository {

    /**
     * 保存消息（异常安全）
     */
    public DatabaseResult<Long> saveMessage(Map<String, Object> data) {
        return safeExecute(() -> {
            String sql = "INSERT INTO messages (session_id, user_id, content, is_robot_reply, " +
                    "is_private, group_id, reply_to_id, topics) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";

            // 提取参数，处理null值
            String sessionId = getStringValue(data, "sessionId", "");
            String userId = getStringValue(data, "userId", "");
            String content = getStringValue(data, "content", "");
            boolean isRobotReply = getBooleanValue(data, "isRobotReply", false);
            boolean isPrivate = getBooleanValue(data, "isPrivate", false);
            String groupId = getStringValue(data, "groupId", null);
            Long replyToId = getLongValue(data, "replyToId", null);
            String topics = getStringValue(data, "topics", null);

            return executeInsert(sql,
                    sessionId, userId, content, isRobotReply, isPrivate, groupId, replyToId, topics
            ).getData();
        });
    }

    /**
     * 根据Session ID查找消息
     */
    public DatabaseResult<List<Map<String, Object>>> findBySessionId(String sessionId, int limit) {
        String sql = "SELECT * FROM messages WHERE session_id = ? " +
                "ORDER BY created_at DESC LIMIT ?";

        return executeQuery(sql, this::mapToHashMap, sessionId, limit);
    }

    /**
     * 获取用户最近的消息
     */
    public DatabaseResult<List<String>> findUserRecentMessages(String userId, int limit) {
        String sql = "SELECT content FROM messages WHERE user_id = ? " +
                "AND is_robot_reply = FALSE ORDER BY created_at DESC LIMIT ?";

        return executeQuery(sql, rs -> {
            try {
                return rs.getString("content");
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }, userId, limit);
    }

    /**
     * 获取群组最近的消息
     */
    public DatabaseResult<List<ChatMessage>> findGroupRecentMessages(String groupId, int limit) {
        String sql = "SELECT * FROM messages WHERE group_id = ? " +
                "ORDER BY created_at DESC LIMIT ?";

        return executeQuery(sql, this::mapToChatMessage, groupId, limit);
    }

    /**
     * 获取对话上下文
     */
    public DatabaseResult<List<ChatMessage>> findConversationContext(String groupId, int minutes, int limit) {
        String sql = "SELECT * FROM messages WHERE group_id = ? " +
                "AND created_at >= DATE_SUB(NOW(), INTERVAL ? MINUTE) " +
                "ORDER BY created_at ASC LIMIT ?";

        return executeQuery(sql, this::mapToChatMessage, groupId, minutes, limit);
    }

    /**
     * 获取未回复的问题
     */
    public DatabaseResult<List<ChatMessage>> findUnrepliedQuestions(String groupId) {
        String sql = "SELECT m1.* FROM messages m1 " +
                "WHERE m1.group_id = ? AND m1.is_robot_reply = FALSE " +
                "AND NOT EXISTS (SELECT 1 FROM messages m2 " +
                "WHERE m2.reply_to_id = m1.id AND m2.is_robot_reply = TRUE) " +
                "ORDER BY m1.created_at DESC LIMIT 5";

        return executeQuery(sql, this::mapToChatMessage, groupId);
    }

    /**
     * 获取用户与机器人的对话历史
     */
    public DatabaseResult<List<ChatMessage>> findUserBotConversation(String groupId, String userId, int limit) {
        String sql = "SELECT * FROM messages WHERE group_id = ? " +
                "AND (user_id = ? OR user_id = 'candybear') " +
                "ORDER BY created_at ASC LIMIT ?";

        return executeQuery(sql, this::mapToChatMessage, groupId, userId, limit);
    }

    /**
     * 保存主动回复决策日志
     */
    public DatabaseResult<Integer> saveActiveReplyLog(Map<String, Object> data) {
        return safeExecute(() -> {
            String sql = "INSERT INTO active_reply_logs " +
                    "(group_id, user_id, message_content, decision, " +
                    "decision_reason, confidence, replied_content) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?)";

            return executeUpdate(sql,
                    getStringValue(data, "groupId", ""),
                    getStringValue(data, "userId", ""),
                    getStringValue(data, "messageContent", ""),
                    getStringValue(data, "decision", ""),
                    getStringValue(data, "decisionReason", ""),
                    getDoubleValue(data, "confidence", 0.5),
                    getStringValue(data, "repliedContent", "")
            ).getData();
        });
    }

    /**
     * 根据ID获取消息
     */
    public DatabaseResult<ChatMessage> findMessageById(Long messageId) {
        String sql = "SELECT * FROM messages WHERE id = ?";

        DatabaseResult<List<ChatMessage>> result = executeQuery(sql, this::mapToChatMessage, messageId);
        if (result.isSuccess()) {
            List<ChatMessage> messages = result.getData();
            return DatabaseResult.success(
                    messages.isEmpty() ? null : messages.get(0)
            );
        } else {
            return DatabaseResult.failure(result.getError());
        }
    }

    /**
     * 统计群组消息数量
     */
    public DatabaseResult<Integer> countGroupMessages(String groupId) {
        String sql = "SELECT COUNT(*) FROM messages WHERE group_id = ?";

        return executeQuerySingle(sql, rs -> {
            try {
                return rs.getInt(1);
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }, groupId);
    }

    /**
     * 获取热门话题
     */
    public DatabaseResult<List<String>> findPopularTopics(String groupId, int days) {
        String sql = "SELECT topics, COUNT(*) as count FROM messages " +
                "WHERE group_id = ? AND topics IS NOT NULL " +
                "AND created_at >= DATE_SUB(NOW(), INTERVAL ? DAY) " +
                "GROUP BY topics ORDER BY count DESC LIMIT 5";

        return executeQuery(sql, rs -> {
            try {
                return rs.getString("topics");
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }, groupId, days);
    }

    // ===== 辅助方法 =====

    private ChatMessage mapToChatMessage(ResultSet rs)  {
        try {


        ChatMessage message = new ChatMessage();
        message.setId(rs.getLong("id"));
        message.setGroupId(rs.getString("group_id"));
        message.setUserId(rs.getString("user_id"));
        message.setContent(rs.getString("content"));
        message.setIsRobotReply(rs.getBoolean("is_robot_reply"));
        message.setIsPrivate(rs.getBoolean("is_private"));

        Long replyToId = rs.getLong("reply_to_id");
        if (!rs.wasNull()) message.setReplyToId(replyToId);

        message.setTopics(rs.getString("topics"));
        message.setSessionId(rs.getString("session_id"));

        Timestamp createdAt = rs.getTimestamp("created_at");
        if (createdAt != null) message.setCreatedAt(createdAt.toLocalDateTime());

        return message;}
        catch (Exception e){
            return null;
        }
    }

    private Map<String, Object> mapToHashMap(ResultSet rs) {
        try{
        Map<String, Object> map = new HashMap<>();
        ResultSetMetaData metaData = rs.getMetaData();

        for (int i = 1; i <= metaData.getColumnCount(); i++) {
            String columnName = metaData.getColumnName(i);
            map.put(columnName, rs.getObject(i));
        }
        return map;}
        catch (Exception e){
            return null;
        }
    }

    private String getStringValue(Map<String, Object> data, String key, String defaultValue) {
        Object value = data.get(key);
        return value != null ? value.toString() : defaultValue;
    }

    private boolean getBooleanValue(Map<String, Object> data, String key, boolean defaultValue) {
        Object value = data.get(key);
        return value instanceof Boolean ? (Boolean) value : defaultValue;
    }

    private Long getLongValue(Map<String, Object> data, String key, Long defaultValue) {
        Object value = data.get(key);
        if (value instanceof Long) return (Long) value;
        if (value instanceof Integer) return ((Integer) value).longValue();
        return defaultValue;
    }

    private Double getDoubleValue(Map<String, Object> data, String key, double defaultValue) {
        Object value = data.get(key);
        if (value instanceof Double) return (Double) value;
        if (value instanceof Number) return ((Number) value).doubleValue();
        return defaultValue;
    }
}