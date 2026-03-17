package com.mcpuploadjava.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.mcpuploadjava.ConfigurationException;
import com.mcpuploadjava.Json;
import com.mcpuploadjava.ProjectPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Loads and validates mcp.json.
 * Supports "embedded" (local in-process files server) and "http" transports.
 */
public final class McpConfig {

    private static final String UPLOADTHING_PLACEHOLDER = "https://URL_TO_YOUR_MCP_SERVER/mcp";

    private final Map<String, ServerConfig> servers;

    private McpConfig(Map<String, ServerConfig> servers) {
        this.servers = servers;
    }

    public Map<String, ServerConfig> servers() { return servers; }

    // -----------------------------------------------------------------------

    public static McpConfig load() throws IOException {
        Path configPath = ProjectPaths.projectRoot().resolve("mcp.json");
        String content  = Files.readString(configPath, StandardCharsets.UTF_8);
        Object parsed   = Json.parseObject(content);
        Map<String, Object> parsedMap = Json.convert(parsed, new TypeReference<>() {});

        @SuppressWarnings("unchecked")
        Map<String, Object> mcpServers = (Map<String, Object>) parsedMap.get("mcpServers");
        if (mcpServers == null) {
            throw new ConfigurationException(
                    "Invalid mcp.json: expected a top-level \"mcpServers\" object");
        }

        Map<String, ServerConfig> servers = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : mcpServers.entrySet()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> serverMap = (Map<String, Object>) entry.getValue();
            String transport = String.valueOf(serverMap.getOrDefault("transport", "embedded"));
            String url       = String.valueOf(serverMap.getOrDefault("url", ""));
            String root      = String.valueOf(serverMap.getOrDefault("root", "./workspace"));
            servers.put(entry.getKey(), new ServerConfig(transport, url, root));
        }
        return new McpConfig(servers);
    }

    /** Throws {@link ConfigurationException} when the HTTP server config is missing or has a placeholder URL. */
    public void validateHttpServer(String serverName, ServerConfig config) {
        String url = config.url().trim();
        if (url.isEmpty()) {
            throw new ConfigurationException(
                    "Invalid mcp.json: set mcpServers." + serverName + ".url");
        }
        if (url.equals(UPLOADTHING_PLACEHOLDER) || url.contains("URL_TO_YOUR_MCP_SERVER")) {
            throw new ConfigurationException(
                    "Invalid mcp.json: replace the mcpServers." + serverName
                    + ".url placeholder with your actual server URL. See README.md for instructions.");
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw new ConfigurationException(
                    "Invalid mcp.json: mcpServers." + serverName + ".url must be an http(s) URL");
        }
    }

    /** Resolves the workspace root path for the embedded files server. */
    public static Path resolveWorkspaceRoot() throws IOException {
        McpConfig config       = load();
        ServerConfig files     = config.servers().get("files");
        String root            = files != null ? files.root() : "./workspace";
        return ProjectPaths.projectRoot().resolve(root).normalize();
    }

    public record ServerConfig(String transport, String url, String root) {}
}
