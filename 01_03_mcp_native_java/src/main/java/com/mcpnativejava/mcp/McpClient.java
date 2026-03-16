package com.mcpnativejava.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.mcpnativejava.ToolDefinitionFactory;

import java.util.List;
import java.util.Map;

public final class McpClient {
    private final McpServer server;

    public McpClient(McpServer server) {
        this.server = server;
    }

    public List<McpServer.McpTool> listTools() {
        return server.listTools();
    }

    public Object callTool(String name, JsonNode args) {
        return server.callTool(name, args);
    }

    public static List<Map<String, Object>> toOpenAiTools(List<McpServer.McpTool> mcpTools) {
        return mcpTools.stream()
                .map(tool -> ToolDefinitionFactory.functionTool(tool.name(), tool.description(), tool.inputSchema()))
                .toList();
    }
}