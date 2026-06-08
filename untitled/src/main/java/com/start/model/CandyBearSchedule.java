package com.start.model;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 糖果熊日程安排：每天不同时段的真实活动。
 */
public class CandyBearSchedule {
    private Long id;
    private LocalDate scheduleDate;
    private String dayOfWeek;       // 周一~周日
    private String timeSlot;        // morning/lunch/afternoon/evening/night
    private LocalTime startTime;
    private LocalTime endTime;
    private String activity;        // 正在做什么
    private String location;        // 在哪里
    private String mood;            // 心情
    private boolean isSchoolDay;    // 是否上学日

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public LocalDate getScheduleDate() { return scheduleDate; }
    public void setScheduleDate(LocalDate scheduleDate) { this.scheduleDate = scheduleDate; }
    public String getDayOfWeek() { return dayOfWeek; }
    public void setDayOfWeek(String dayOfWeek) { this.dayOfWeek = dayOfWeek; }
    public String getTimeSlot() { return timeSlot; }
    public void setTimeSlot(String timeSlot) { this.timeSlot = timeSlot; }
    public LocalTime getStartTime() { return startTime; }
    public void setStartTime(LocalTime startTime) { this.startTime = startTime; }
    public LocalTime getEndTime() { return endTime; }
    public void setEndTime(LocalTime endTime) { this.endTime = endTime; }
    public String getActivity() { return activity; }
    public void setActivity(String activity) { this.activity = activity; }
    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }
    public String getMood() { return mood; }
    public void setMood(String mood) { this.mood = mood; }
    public boolean isSchoolDay() { return isSchoolDay; }
    public void setSchoolDay(boolean schoolDay) { isSchoolDay = schoolDay; }
}
