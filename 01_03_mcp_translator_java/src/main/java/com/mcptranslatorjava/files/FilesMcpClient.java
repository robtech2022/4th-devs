package com.mcptranslatorjava.files;

import com.fasterxml.jackson.core.type.TypeReference;
import com.mcptranslatorjava.Json;
import com.mcptranslatorjava.Log;
import com.mcptranslatorjava.ProjectPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public final class FilesMcpClient {
    private final EmbeddedFilesServer server;

    private FilesMcpClient(EmbeddedFilesServer server) {
        this.server = server;
    }

    public static FilesMcpClient connect() throws IOException {
        Path projectRoot = ProjectPaths.projectRoot();
        Path configPath = projectRoot.resolve("mcp.json");
        Object parsed = Json.parseObject(Files.readString(configPath, StandardCharsets.UTF_8));
        Map<String, Object> parsedMap = Json.convert(parsed, new TypeReference<>() {});
        @SuppressWarnings("unchecked")
        Map<String, Object> mcpServers = (Map<String, Object>) parsedMap.get("mcpServers");
        @SuppressWarnings("unchecked")
        Map<String, Object> filesConfig = (Map<String, Object>) mcpServers.get("files");
        String root = String.valueOf(filesConfig.getOrDefault("root", "./workspace"));
        Path workspaceRoot = projectRoot.resolve(root).normalize();
        Files.createDirectories(workspaceRoot);

        Log.info("Spawning MCP server: files");
        Log.info("Embedded root: " + workspaceRoot);

        return new FilesMcpClient(new EmbeddedFilesServer(workspaceRoot));
    }

    public List<Map<String, Object>> listTools() {
        return server.listToolDefinitions();
    }

    public Object callTool(String name, Map<String, Object> args) throws IOException {
        return server.callTool(name, args);
    }

    public void close() {
    }
}
