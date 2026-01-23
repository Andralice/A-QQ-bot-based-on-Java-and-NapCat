package com.start.service;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.start.config.DatabaseConfig;
import com.start.model.ChatMessage;
import com.start.model.UserAffinity;
import com.start.model.UserProfile;
import com.start.repository.MessageRepository;
import com.start.repository.UserAffinityRepository;
import com.start.repository.UserProfileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

public class UserPortraitService {
    Logger logger = LoggerFactory.getLogger(UserPortraitService.class);
    private final BaiLianService baiLianService; // 假设你有这个类
    private final MessageRepository messageRepo;
    private final UserProfileRepository profileRepo = new UserProfileRepository();
    private final UserAffinityRepository affinityRepo = new UserAffinityRepository();
    private static final ObjectMapper jsonMapper = new ObjectMapper();

    private static final int MIN_NEW_MESSAGES = 20;
    private static final int MAX_MESSAGES_TO_ANALYZE = 50;

    public UserPortraitService(BaiLianService baiLianService, MessageRepository messageRepo) {
        this.baiLianService = baiLianService;
        this.messageRepo = messageRepo;
    }

    // 获取需要更新的用户列表（简化版：遍历最近活跃用户）
    public List<Map<String, Object>> getCandidates() throws SQLException {
        String sql = """
    SELECT 
        m.user_id,
        m.group_id,
        COALESCE(MAX(p.last_message_id), 0) AS last_profile_id,
        COALESCE(MAX(a.last_updated_message_id), 0) AS last_affinity_id,
        COUNT(*) AS new_msg_count
    FROM messages m
    LEFT JOIN user_profiles p 
        ON m.user_id = p.user_id AND (m.group_id <=> p.group_id)
    LEFT JOIN user_affinity a 
        ON m.user_id = a.user_id AND (m.group_id <=> a.group_id)
    WHERE 
        m.is_robot_reply = 0
        AND m.id > GREATEST(
            COALESCE(p.last_message_id, 0),
            COALESCE(a.last_updated_message_id, 0)
        )
    GROUP BY m.user_id, m.group_id
    HAVING new_msg_count >= ?
    """;

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, MIN_NEW_MESSAGES);
            ResultSet rs = ps.executeQuery();
            List<Map<String, Object>> list = new ArrayList<>();
            while (rs.next()) {
                Map<String, Object> row = new HashMap<>();
                row.put("userId", rs.getString("user_id"));
                row.put("groupId", rs.getString("group_id"));
                row.put("lastId", Math.max(rs.getLong("last_profile_id"), rs.getLong("last_affinity_id")));
                list.add(row);
            }
            return list;
        }
    }

    public void processUser(String userId, String groupId, long lastMessageId) throws Exception {
        // 获取新消息
        var result = messageRepo.findMessagesAfterId(userId, groupId, lastMessageId, MAX_MESSAGES_TO_ANALYZE);
        if (!result.isSuccess() || result.getData().isEmpty()) return;

        List<ChatMessage> newMessages = result.getData();
        long newestId = newMessages.get(newMessages.size() - 1).getId();
        int totalAnalyzed = 0;

        Optional<UserProfile> profileOpt = profileRepo.findByUserIdAndGroupId(userId, groupId);
        Optional<UserAffinity> affinityOpt = affinityRepo.findByUserIdAndGroupId(userId, groupId);

        if (profileOpt.isPresent()) {
            totalAnalyzed = profileOpt.get().getMessageCountSnapshot() + newMessages.size();
        } else {
            totalAnalyzed = newMessages.size();
        }

        String history = newMessages.stream()
                .map(m -> "- " + m.getContent())
                .collect(Collectors.joining("\n"));

        StringBuilder prompt = new StringBuilder();
        prompt.append("你是一个用户行为分析师，目前的身份是qq聊天群内的一个群员，请根据以下信息更新你对用户画像和好感度，好感度根据主动聊天次数和说话的友好程度决定,用户画像可以保留对该群员的一些兴趣介绍等，之前的用户画像要总结进新的里，保留真实知识（该群员的兴趣之类）。\n\n");
        if (profileOpt.isPresent()) {
            prompt.append("【当前画像】\n").append(profileOpt.get().getProfileText()).append("\n\n");
        }
        prompt.append("【新增聊天记录】\n").append(history).append("\n\n");
        prompt.append("""
请严格按以下 JSON 格式输出：
{
  "new_profile": "更新后的画像文本（约100字）",
  "affinity_change": {"delta": 整数（-5到+5）, "reason": "简短原因"}
}
""");

        String aiResponse = baiLianService.generateForAgent(prompt.toString(), Collections.emptyList());
        logger.debug("🤖 响应: " + aiResponse);
        JsonNode root = jsonMapper.readTree(aiResponse);
        String newProfile = root.path("new_profile").asText("未生成画像");
        int delta = root.path("affinity_change").path("delta").asInt(0);
        String reason = root.path("affinity_change").path("reason").asText("无");

        // 保存画像
        UserProfile profile = new UserProfile();
        profile.setUserId(userId);
        profile.setGroupId(groupId);
        profile.setProfileText(newProfile);
        profile.setMessageCountSnapshot(totalAnalyzed);
        profile.setLastMessageId(newestId);
        profileRepo.saveOrUpdate(profile);

        // 保存好感度
        int oldScore = affinityOpt.map(UserAffinity::getAffinityScore).orElse(50);
        int newScore = Math.max(0, Math.min(100, oldScore + delta));

        List<String> reasons = new ArrayList<>();
        if (affinityOpt.isPresent() && affinityOpt.get().getReasonLog() != null) {
            try {
                JsonNode logArray = jsonMapper.readTree(affinityOpt.get().getReasonLog());
                if (logArray.isArray()) {
                    for (JsonNode node : logArray) {
                        reasons.add(node.asText());
                    }
                }
            } catch (Exception ignored) {}
        }
        reasons.add(String.format("%+d: %s", delta, reason));
        if (reasons.size() > 10) {
            reasons = reasons.subList(reasons.size() - 10, reasons.size());
        }
        String reasonJson = jsonMapper.writeValueAsString(reasons);

        UserAffinity affinity = new UserAffinity();
        affinity.setUserId(userId);
        affinity.setGroupId(groupId);
        affinity.setAffinityScore(newScore);
        affinity.setLastUpdatedMessageId(newestId);
        affinity.setMessageCountSnapshot(totalAnalyzed);
        affinity.setReasonLog(reasonJson);
        affinityRepo.saveOrUpdate(affinity);
    }

    public void runUpdateTask() {
        try {
            List<Map<String, Object>> candidates = getCandidates();
            for (Map<String, Object> candidate : candidates) {
                String userId = (String) candidate.get("userId");
                String groupId = (String) candidate.get("groupId");
                long lastId = (Long) candidate.get("lastId");
                try {
                    processUser(userId, groupId, lastId);
                    System.out.println("✅ 更新画像: " + userId + " @ " + groupId);
                } catch (Exception e) {
                    System.err.println("❌ 处理失败: " + userId + " @ " + groupId + " - " + e.getMessage());
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}