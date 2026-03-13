package com.toolusejava;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ToolHandlers {
    private final SandboxUtils sandbox;

    public ToolHandlers(SandboxUtils sandbox) {
        this.sandbox = sandbox;
    }

    public Object execute(String toolName, JsonNode args) throws Exception {
        return switch (toolName) {
            case "list_files" -> listFiles(args);
            case "read_file" -> readFile(args);
            case "write_file" -> writeFile(args);
            case "delete_file" -> deleteFile(args);
            case "create_directory" -> createDirectory(args);
            case "file_info" -> fileInfo(args);
            default -> throw new RuntimeException("Unknown tool: " + toolName);
        };
    }

    private List<Map<String, Object>> listFiles(JsonNode args) throws IOException {
        Path fullPath = sandbox.resolveSandboxPath(requireText(args.path("path").asText(null), "path"));
        try (var entries = Files.list(fullPath)) {
            List<Map<String, Object>> items = new ArrayList<>();
            entries.forEach(entry -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("name", entry.getFileName().toString());
                item.put("type", Files.isDirectory(entry) ? "directory" : "file");
                items.add(item);
            });
            return items;
        }
    }

    private Map<String, Object> readFile(JsonNode args) throws IOException {
        String relativePath = requireText(args.path("path").asText(null), "path");
        Path fullPath = sandbox.resolveSandboxPath(relativePath);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("content", Files.readString(fullPath, StandardCharsets.UTF_8));
        return out;
    }

    private Map<String, Object> writeFile(JsonNode args) throws IOException {
        String relativePath = requireText(args.path("path").asText(null), "path");
        String content = args.path("content").asText("");
        Path fullPath = sandbox.resolveSandboxPath(relativePath);

        if (fullPath.getParent() != null) {
            Files.createDirectories(fullPath.getParent());
        }

        Files.writeString(fullPath, content, StandardCharsets.UTF_8);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", true);
        out.put("message", "File written: " + relativePath);
        return out;
    }

    private Map<String, Object> deleteFile(JsonNode args) throws IOException {
        String relativePath = requireText(args.path("path").asText(null), "path");
        Path fullPath = sandbox.resolveSandboxPath(relativePath);
        Files.delete(fullPath);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", true);
        out.put("message", "File deleted: " + relativePath);
        return out;
    }

    private Map<String, Object> createDirectory(JsonNode args) throws IOException {
        String relativePath = requireText(args.path("path").asText(null), "path");
        Path fullPath = sandbox.resolveSandboxPath(relativePath);
        Files.createDirectories(fullPath);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", true);
        out.put("message", "Directory created: " + relativePath);
        return out;
    }

    private Map<String, Object> fileInfo(JsonNode args) throws IOException {
        String relativePath = requireText(args.path("path").asText(null), "path");
        Path fullPath = sandbox.resolveSandboxPath(relativePath);
        BasicFileAttributes attr = Files.readAttributes(fullPath, BasicFileAttributes.class);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("size", attr.size());
        out.put("isDirectory", attr.isDirectory());
        out.put("created", DateTimeFormatter.ISO_INSTANT.format(attr.creationTime().toInstant().atOffset(ZoneOffset.UTC)));
        out.put("modified", DateTimeFormatter.ISO_INSTANT.format(attr.lastModifiedTime().toInstant().atOffset(ZoneOffset.UTC)));
        return out;
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new RuntimeException("\"" + fieldName + "\" must be a non-empty string.");
        }
        return value.trim();
    }
}
