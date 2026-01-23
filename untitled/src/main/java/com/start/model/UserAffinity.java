package com.start.model;


import java.time.LocalDateTime;

public class UserAffinity {
    private Long id;
    private String userId;
    private String groupId;
    private Integer affinityScore = 50;
    private Long lastUpdatedMessageId;
    private Integer messageCountSnapshot = 0;
    private String reasonLog; // JSON 字符串
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getGroupId() { return groupId; }
    public void setGroupId(String groupId) { this.groupId = groupId; }

    public Integer getAffinityScore() { return affinityScore; }
    public void setAffinityScore(Integer affinityScore) { this.affinityScore = affinityScore; }

    public Long getLastUpdatedMessageId() { return lastUpdatedMessageId; }
    public void setLastUpdatedMessageId(Long lastUpdatedMessageId) { this.lastUpdatedMessageId = lastUpdatedMessageId; }

    public Integer getMessageCountSnapshot() { return messageCountSnapshot; }
    public void setMessageCountSnapshot(Integer messageCountSnapshot) { this.messageCountSnapshot = messageCountSnapshot; }

    public String getReasonLog() { return reasonLog; }
    public void setReasonLog(String reasonLog) { this.reasonLog = reasonLog; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}