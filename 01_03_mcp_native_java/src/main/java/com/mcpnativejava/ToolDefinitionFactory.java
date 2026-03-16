package com.mcpnativejava;

import java.util.LinkedHashMap;
import java.util.Map;

public final class ToolDefinitionFactory {
    private ToolDefinitionFactory() {
    }

    public static Map<String, Object> functionTool(String name, String description, Map<String, Object> parameters) {
        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("type", "function");
        tool.put("name", name);
        tool.put("description", description);
        tool.put("parameters", parameters);
        tool.put("strict", true);
        return tool;
    }
}