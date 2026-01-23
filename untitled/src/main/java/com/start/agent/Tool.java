package com.start.agent;

import java.util.Map;

public interface Tool {
    String getName();
    String getDescription();
    Map<String, Object> getParameters(); // JSON Schema

    /**
     * 返回符合百炼 API 要求的 function spec
     */
    default Map<String, Object> getFunctionSpec() {
        return Map.of(
                "type", "function",
                "function", Map.of(
                        "name", getName(),
                        "description", getDescription(),
                        "parameters", getParameters()
                )
        );
    }

    String execute(Map<String, Object> args);
}