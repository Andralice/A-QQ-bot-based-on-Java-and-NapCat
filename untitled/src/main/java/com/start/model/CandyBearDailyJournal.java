package com.start.model;

import java.time.LocalDate;

/** 糖果熊日记：每天结束后 AI 写的当日小结。 */
public class CandyBearDailyJournal {
    private Long id;
    private LocalDate journalDate;
    private String importantEvents;  // JSON 数组
    private String emotion;
    private String summary;
    private LocalDate createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public LocalDate getJournalDate() { return journalDate; }
    public void setJournalDate(LocalDate journalDate) { this.journalDate = journalDate; }
    public String getImportantEvents() { return importantEvents; }
    public void setImportantEvents(String importantEvents) { this.importantEvents = importantEvents; }
    public String getEmotion() { return emotion; }
    public void setEmotion(String emotion) { this.emotion = emotion; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public LocalDate getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDate createdAt) { this.createdAt = createdAt; }
}
