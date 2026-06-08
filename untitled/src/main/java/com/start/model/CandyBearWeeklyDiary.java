package com.start.model;

import java.time.LocalDate;

/** 糖果熊周记：每周日 AI 总结本周 + 规划下周。 */
public class CandyBearWeeklyDiary {
    private Long id;
    private LocalDate weekStart;
    private LocalDate weekEnd;
    private String summary;
    private String majorEvents;      // JSON 数组
    private String emotion;
    private String nextWeekPlan;
    private LocalDate createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public LocalDate getWeekStart() { return weekStart; }
    public void setWeekStart(LocalDate weekStart) { this.weekStart = weekStart; }
    public LocalDate getWeekEnd() { return weekEnd; }
    public void setWeekEnd(LocalDate weekEnd) { this.weekEnd = weekEnd; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public String getMajorEvents() { return majorEvents; }
    public void setMajorEvents(String majorEvents) { this.majorEvents = majorEvents; }
    public String getEmotion() { return emotion; }
    public void setEmotion(String emotion) { this.emotion = emotion; }
    public String getNextWeekPlan() { return nextWeekPlan; }
    public void setNextWeekPlan(String nextWeekPlan) { this.nextWeekPlan = nextWeekPlan; }
    public LocalDate getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDate createdAt) { this.createdAt = createdAt; }
}
