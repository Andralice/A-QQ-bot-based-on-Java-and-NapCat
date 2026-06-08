package com.start.service;

import com.start.repository.BotMemoryRepository;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 糖果熊短期记忆：记录最近说的话、做的事、调的工具。
 * 内存缓存 + 数据库持久化，重启后可从 DB 恢复。
 */
public class BotMemoryService {

    private final Map<String, Deque<MemoryEntry>> groupMemory = new ConcurrentHashMap<>();
    private final BotMemoryRepository dbRepo;
    private static final int MAX_ENTRIES = 80;

    public enum EntryType {
        SAID,
        PRIVATE_SENT,
        TOOL_CALLED,
        POKED,
        VOICE,
        REMINDER_SET,
        GAME_STARTED
    }

    public record MemoryEntry(long timestamp, EntryType type, String target, String detail) {
        public String toString() {
            long ago = (System.currentTimeMillis() - timestamp) / 1000;
            String time = ago < 60 ? ago + "秒前" : ago < 3600 ? (ago / 60) + "分钟前" : (ago / 3600) + "小时前";
            return time + " | " + type + " | " + (target != null ? target : "") + " | " + detail;
        }
    }

    public BotMemoryService(BotMemoryRepository dbRepo) {
        this.dbRepo = dbRepo;
    }

    public void record(String groupId, EntryType type, String target, String detail) {
        Deque<MemoryEntry> q = groupMemory.computeIfAbsent(groupId, k -> new ConcurrentLinkedDeque<>());
        q.addLast(new MemoryEntry(System.currentTimeMillis(), type, target, detail));
        while (q.size() > MAX_ENTRIES) q.removeFirst();

        // 持久化到数据库
        if (dbRepo != null) {
            try {
                dbRepo.insert(groupId, type, target, detail);
            } catch (Exception e) {
                // DB 写入失败不影响主流程
            }
        }
    }

    /** 查询最近的记忆（内存 + DB 合并） */
    public String query(String groupId, int count, String typeFilter, String keyword) {
        Deque<MemoryEntry> q = groupMemory.get(groupId);

        // 同时查 DB
        List<String> dbEntries = Collections.emptyList();
        if (dbRepo != null) {
            try {
                dbEntries = dbRepo.query(groupId, count, typeFilter, keyword);
            } catch (Exception ignored) {}
        }

        boolean hasMemory = (q != null && !q.isEmpty());
        boolean hasDb = !dbEntries.isEmpty();

        if (!hasMemory && !hasDb) {
            return "你还没有做过任何事，没有记忆记录。如实告诉用户即可，不要编理由。";
        }

        StringBuilder sb = new StringBuilder("糖果熊最近做的事：\n");
        int shown = 0;

        // 先展示内存中的（最新）
        if (hasMemory) {
            List<MemoryEntry> list = new ArrayList<>(q);
            Collections.reverse(list);
            for (MemoryEntry e : list) {
                if (typeFilter != null && !typeFilter.isEmpty() && !e.type.name().contains(typeFilter.toUpperCase())) continue;
                if (keyword != null && !keyword.isEmpty() && !e.detail.contains(keyword) && (e.target == null || !e.target.contains(keyword))) continue;
                sb.append("- ").append(e.toString()).append("\n");
                shown++;
                if (count > 0 && shown >= count) break;
            }
        }

        // 补充 DB 中的（去重）
        if (hasDb && (count <= 0 || shown < count)) {
            Set<String> seen = new HashSet<>();
            if (hasMemory) {
                for (MemoryEntry e : q) {
                    seen.add(e.type + "|" + (e.target != null ? e.target : "") + "|" + e.detail);
                }
            }
            for (String entry : dbEntries) {
                if (count > 0 && shown >= count) break;
                // 简单去重：已展示过的不再重复
                String key = entry.substring(entry.indexOf("|") + 3); // 去掉时间前缀
                if (!seen.add(key)) continue;
                sb.append("- ").append(entry).append("\n");
                shown++;
            }
        }

        if (shown == 0) sb.append("（没有匹配的记录，如实告诉用户，不要编理由）");
        return sb.toString();
    }
}
