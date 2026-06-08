package com.start.agent;

import com.start.service.CandyBearLifeEngine;

import java.util.*;

/**
 * 糖果熊查询自己的人生状态。返回当前章节、最近日记、本周计划。
 * 数据来自 CandyBearLifeEngine 的四层人生架构，保证连续性。
 */
public class QueryLifeTool implements Tool {
    private final CandyBearLifeEngine engine;

    public QueryLifeTool(CandyBearLifeEngine engine) {
        this.engine = engine;
    }

    @Override public String getName() { return "query_life"; }

    @Override public String getDescription() {
        return "查询糖果熊自己的真实生活状态。包括当前在经历什么（章节）、最近几天做了什么（日记）、接下来打算做什么（计划）。" +
               "当你需要描述自己的日常生活、回答最近在干嘛、或者想说自己的近况时，必须先调这个查，不要瞎编。" +
               "你的生活是连续的——上周的日记会自然延续到这周，不会出现前后矛盾。";
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of("type", "object", "properties", Map.of(), "required", List.of());
    }

    @Override
    public String execute(Map<String, Object> args) {
        return engine.queryLifeContext();
    }
}
