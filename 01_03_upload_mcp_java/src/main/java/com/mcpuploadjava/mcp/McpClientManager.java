package com.mcpuploadjava.mcp;

import com.mcpuploadjava.Log;
import com.mcpuploadjava.files.FilesMcpClient;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Connects to all servers defined in mcp.json and exposes them through a single façade.
 * Tool names are prefixed with the server name: e.g. {@code files__fs_read},
 * {@code uploadthing__upload_files}.
 */
public final class McpClientManager {

    private final Map<String, McpClient> clients;
    private Path filesWorkspaceRoot;

    private McpClientManager(Map<String, McpClient> clients, Path filesWorkspaceRoot) {
        this.clients            = clients;
        this.filesWorkspaceRoot = filesWorkspaceRoot;
    }

    // -----------------------------------------------------------------------
    // Factory
    // -----------------------------------------------------------------------

    public static McpClientManager connect() throws Exception {
        McpConfig config = McpConfig.load();
        Map<String, McpClient> clients = new LinkedHashMap<>();
        Path workspaceRoot = null;

        try {
            for (Map.Entry<String, McpConfig.ServerConfig> entry : config.servers().entrySet()) {
                String serverName             = entry.getKey();
                McpConfig.ServerConfig svcCfg = entry.getValue();

                McpClient client;
                if ("http".equals(svcCfg.transport())) {
                    config.validateHttpServer(serverName, svcCfg);
                    Log.info("Connecting to HTTP server: " + svcCfg.url());
                    client = new HttpMcpClient(serverName, svcCfg.url());
                } else {
                    // "embedded" or "stdio" → use the in-process files server
                    Log.info("Spawning embedded server: " + serverName);
                    FilesMcpClient fc = FilesMcpClient.connect(svcCfg);
                    client = fc;
                    if (workspaceRoot == null) workspaceRoot = fc.workspaceRoot();
                }

                clients.put(serverName, client);
                Log.success("Connected to " + serverName + " via " + svcCfg.transport());
            }
        } catch (Exception ex) {
            // Close already-connected clients before re-throwing
            clients.values().forEach(c -> { try { c.close(); } catch (Exception ignored) {} });
            throw ex;
        }

        return new McpClientManager(clients, workspaceRoot);
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /** Lists all tools from all servers, prefixed with {@code <serverName>__}. */
    public List<Map<String, Object>> listAllTools() throws Exception {
        List<Map<String, Object>> allTools = new ArrayList<>();
        for (Map.Entry<String, McpClient> entry : clients.entrySet()) {
            String serverName = entry.getKey();
            List<Map<String, Object>> tools = entry.getValue().listTools();
            Log.info("  " + serverName + ": "
                    + tools.stream().map(t -> String.valueOf(t.get("name"))).toList());
            for (Map<String, Object> tool : tools) {
                Map<String, Object> prefixed = new LinkedHashMap<>(tool);
                prefixed.put("name", serverName + "__" + tool.get("name"));
                allTools.add(prefixed);
            }
        }
        return allTools;
    }

    /**
     * Calls a tool identified by {@code serverName__toolName}.
     *
     * @param prefixedName tool name including server prefix
     * @param args         tool arguments (already resolved — no placeholders)
     */
    public Object callTool(String prefixedName, Map<String, Object> args) throws Exception {
        String[] parts = prefixedName.split("__", 2);
        if (parts.length < 2) {
            throw new RuntimeException("Invalid prefixed tool name (expected server__tool): " + prefixedName);
        }
        McpClient client = clients.get(parts[0]);
        if (client == null) {
            throw new RuntimeException("Unknown MCP server: " + parts[0]);
        }
        return client.callTool(parts[1], args);
    }

    /** Number of connected servers. */
    public int serverCount() { return clients.size(); }

    /**
     * Workspace root used by the embedded files server, or {@code null} if no embedded
     * server is configured.
     */
    public Path filesWorkspaceRoot() { return filesWorkspaceRoot; }

    /** Closes all client connections. */
    public void closeAll() {
        clients.values().forEach(c -> { try { c.close(); } catch (Exception ignored) {} });
    }
}
