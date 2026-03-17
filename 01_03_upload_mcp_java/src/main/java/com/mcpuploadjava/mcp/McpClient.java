package com.mcpuploadjava.mcp;

import java.util.List;
import java.util.Map;

/** Minimal interface for a connected MCP server client. */
public interface McpClient {
    List<Map<String, Object>> listTools() throws Exception;
    Object callTool(String name, Map<String, Object> args) throws Exception;
    void close();
}
