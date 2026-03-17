package com.mcpuploadjava.files;

import com.mcpuploadjava.Log;
import com.mcpuploadjava.ProjectPaths;
import com.mcpuploadjava.mcp.McpClient;
import com.mcpuploadjava.mcp.McpConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Wraps {@link EmbeddedFilesServer} as an {@link McpClient}. */
public final class FilesMcpClient implements McpClient {

    private final EmbeddedFilesServer server;
    private final Path workspaceRoot;

    private FilesMcpClient(EmbeddedFilesServer server, Path workspaceRoot) {
        this.server        = server;
        this.workspaceRoot = workspaceRoot;
    }

    public static FilesMcpClient connect(McpConfig.ServerConfig config) throws IOException {
        Path projectRoot   = ProjectPaths.projectRoot();
        Path workspaceRoot = projectRoot.resolve(config.root()).normalize();
        Files.createDirectories(workspaceRoot);
        Log.info("Embedded root: " + workspaceRoot);
        return new FilesMcpClient(new EmbeddedFilesServer(workspaceRoot), workspaceRoot);
    }

    /** The absolute path of the workspace directory this client is rooted at. */
    public Path workspaceRoot() { return workspaceRoot; }

    @Override
    public List<Map<String, Object>> listTools() {
        return server.listToolDefinitions();
    }

    @Override
    public Object callTool(String name, Map<String, Object> args) throws IOException {
        return server.callTool(name, args);
    }

    @Override
    public void close() {
        // No cleanup needed for embedded server
    }
}
