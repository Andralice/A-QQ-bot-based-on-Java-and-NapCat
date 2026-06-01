package com.start.agent;

import com.start.service.BotMemoryService;

import java.util.*;

/**
 * AI 查询自己的短期记忆：刚才说了什么、做了什么。
 */
public class MemoryTool implements Tool {
    private final BotMemoryService memory;

    public MemoryTool(BotMemoryService memory) { this.memory = memory; }

    @Override public String getName() { return "query_memory"; }

    @Override public String getDescription() {
        return "查询糖果熊自己的短期记忆：最近说了什么、给谁发了私聊、调了什么工具。" +
               "当你忘了自己刚才做过什么，或者用户问'你刚才说了什么''你@了谁'时调用。" +
               "参数：count(返回几条，默认10), type(筛选类型:SAID/PRIVATE_SENT/TOOL_CALLED/POKED/VOICE，不填则全部), keyword(搜索关键词,不填则全部)";
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of("type", "object",
                "properties", Map.of(
                        "group_id", Map.of("type", "string", "description", "群号"),
                        "count", Map.of("type", "string", "description", "返回条数，默认10"),
                        "type", Map.of("type", "string", "description", "筛选类型：SAID/PRIVATE_SENT/TOOL_CALLED/POKED"),
                        "keyword", Map.of("type", "string", "description", "搜索关键词")
                ),
                "required", Arrays.asList("group_id"));
    }

    @Override
    public String execute(Map<String, Object> args) {
        String groupId = (String) args.get("group_id");
        int count = parseIntSafe((String) args.get("count"), 10);
        String typeFilter = (String) args.get("type");
        String keyword = (String) args.get("keyword");

        if (groupId == null) return "缺少 group_id 参数";
        return memory.query(groupId, count, typeFilter, keyword);
    }

    private int parseIntSafe(String s, int def) {
        if (s == null) return def;
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return def; }
    }
}
