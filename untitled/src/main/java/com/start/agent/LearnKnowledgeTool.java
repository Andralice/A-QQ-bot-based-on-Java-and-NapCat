package com.start.agent;

import com.start.service.KeywordKnowledgeService;

import java.util.*;

/**
 * 糖果熊学知识工具。门槛高——只有群友明确教她、纠正她，
 * 或者她发现知识库缺了重要信息时才写入。日常聊天绝不动。
 */
public class LearnKnowledgeTool implements Tool {

    private final KeywordKnowledgeService knowledgeService;

    public LearnKnowledgeTool(KeywordKnowledgeService knowledgeService) {
        this.knowledgeService = knowledgeService;
    }

    @Override public String getName() { return "learn_knowledge"; }

    @Override
    public String getDescription() {
        return "写入知识库。极少使用！仅当群友明确说'记住''记一下'教你东西，" +
               "或你发现知识库缺了重要信息时才调用。普通聊天不能用。";
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of("type", "object",
                "properties", Map.of(
                        "pattern", Map.of("type", "string",
                                "description", "问题模式，多个用|分隔，如'什么是糖果熊|糖果熊是谁'"),
                        "answer", Map.of("type", "string",
                                "description", "回答内容"),
                        "category", Map.of("type", "string",
                                "description", "分类标签，如'群规''游戏''设定'"),
                        "priority", Map.of("type", "string",
                                "description", "优先级 1-10，重要知识给 8-10，普通给 5，默认 5")
                ),
                "required", Arrays.asList("pattern", "answer", "category"));
    }

    @Override
    public String execute(Map<String, Object> args) {
        String pattern = (String) args.get("pattern");
        String answer = (String) args.get("answer");
        String category = (String) args.get("category");
        int priority = parseIntSafe((String) args.get("priority"), 5);

        if (pattern == null || pattern.isBlank()) return "缺少 pattern";
        if (answer == null || answer.isBlank()) return "缺少 answer";
        if (category == null || category.isBlank()) return "缺少 category";
        if (priority < 1 || priority > 10) priority = 5;

        boolean ok = knowledgeService.addKnowledge(pattern.trim(), answer.trim(), category.trim(), priority);
        return ok ? "知识已记录" : "知识写入失败";
    }

    private int parseIntSafe(String s, int def) {
        if (s == null) return def;
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return def; }
    }
}
