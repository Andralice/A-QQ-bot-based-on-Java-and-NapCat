package com.start.model;

import java.time.LocalDate;

/** 糖果熊当前生活章节，持续 2~3 周。 */
public class CandyBearStoryArc {
    private Long id;
    private String arcName;          // 如"月考准备""暑假计划"
    private LocalDate startDate;
    private LocalDate endDate;
    private String summary;
    private String majorEvents;      // JSON 数组字符串
    private String moodTrend;        // 情绪趋势
    private boolean active = true;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getArcName() { return arcName; }
    public void setArcName(String arcName) { this.arcName = arcName; }
    public LocalDate getStartDate() { return startDate; }
    public void setStartDate(LocalDate startDate) { this.startDate = startDate; }
    public LocalDate getEndDate() { return endDate; }
    public void setEndDate(LocalDate endDate) { this.endDate = endDate; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public String getMajorEvents() { return majorEvents; }
    public void setMajorEvents(String majorEvents) { this.majorEvents = majorEvents; }
    public String getMoodTrend() { return moodTrend; }
    public void setMoodTrend(String moodTrend) { this.moodTrend = moodTrend; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
}
