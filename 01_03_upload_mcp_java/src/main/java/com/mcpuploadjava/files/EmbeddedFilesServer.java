package com.mcpuploadjava.files;

import com.mcpuploadjava.Json;
import com.mcpuploadjava.ToolDefinitionFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Pure-Java, in-process implementation of the MCP files server.
 * All operations are sandboxed to {@code workspaceRoot}.
 */
public final class EmbeddedFilesServer {

    private final Path workspaceRoot;

    public EmbeddedFilesServer(Path workspaceRoot) {
        this.workspaceRoot = workspaceRoot.normalize();
    }

    // -----------------------------------------------------------------------
    // Tool definitions
    // -----------------------------------------------------------------------

    public List<Map<String, Object>> listToolDefinitions() {
        return List.of(
                ToolDefinitionFactory.functionTool(
                        "fs_read",
                        "Read files or list directories inside the workspace.",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "path",      Map.of("type", "string"),
                                        "mode",      Map.of("type", "string", "enum", List.of("list", "text")),
                                        "details",   Map.of("type", "boolean"),
                                        "startLine", Map.of("type", "integer"),
                                        "endLine",   Map.of("type", "integer")
                                ),
                                "required",             List.of("path", "mode"),
                                "additionalProperties", false
                        )
                ),
                ToolDefinitionFactory.functionTool(
                        "fs_search",
                        "Search for file names or content inside the workspace.",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "path",      Map.of("type", "string"),
                                        "query",     Map.of("type", "string"),
                                        "inContent", Map.of("type", "boolean")
                                ),
                                "required",             List.of("query"),
                                "additionalProperties", false
                        )
                ),
                ToolDefinitionFactory.functionTool(
                        "fs_write",
                        "Create or update files inside the workspace.",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "path",      Map.of("type", "string"),
                                        "content",   Map.of("type", "string"),
                                        "operation", Map.of("type", "string", "enum", List.of("create", "update")),
                                        "action",    Map.of("type", "string", "enum", List.of("overwrite", "insert_after"))
                                ),
                                "required",             List.of("path", "content", "operation"),
                                "additionalProperties", false
                        )
                ),
                ToolDefinitionFactory.functionTool(
                        "fs_manage",
                        "Manage workspace directories and files.",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "operation", Map.of("type", "string", "enum", List.of("mkdir", "delete")),
                                        "path",      Map.of("type", "string"),
                                        "recursive", Map.of("type", "boolean")
                                ),
                                "required",             List.of("operation", "path"),
                                "additionalProperties", false
                        )
                )
        );
    }

    // -----------------------------------------------------------------------
    // Tool dispatch
    // -----------------------------------------------------------------------

    public Object callTool(String name, Map<String, Object> args) throws IOException {
        return switch (name) {
            case "fs_read"   -> fsRead(args);
            case "fs_search" -> fsSearch(args);
            case "fs_write"  -> fsWrite(args);
            case "fs_manage" -> fsManage(args);
            default -> throw new RuntimeException("Unknown tool: " + name);
        };
    }

    // -----------------------------------------------------------------------
    // Implementations
    // -----------------------------------------------------------------------

    private Object fsRead(Map<String, Object> args) throws IOException {
        String path = requireString(args, "path");
        String mode = requireString(args, "mode");
        Path resolved = resolve(path);

        if ("list".equals(mode)) {
            boolean details = Boolean.TRUE.equals(args.get("details"));
            if (!Files.isDirectory(resolved)) {
                throw new RuntimeException("Path is not a directory: " + path);
            }
            List<Map<String, Object>> entries = new ArrayList<>();
            try (Stream<Path> stream = Files.list(resolved)) {
                stream.sorted().forEach(e -> entries.add(entryDetails(e, details)));
            }
            return Map.of("entries", entries);
        }

        if (!"text".equals(mode)) {
            throw new RuntimeException("Unsupported fs_read mode: " + mode);
        }
        if (!Files.exists(resolved) || Files.isDirectory(resolved)) {
            throw new RuntimeException("Path is not a readable file: " + path);
        }

        List<String> lines      = Files.readAllLines(resolved, StandardCharsets.UTF_8);
        int          totalLines = lines.size();
        Integer      startLine  = asInteger(args.get("startLine"));
        Integer      endLine    = asInteger(args.get("endLine"));
        int          start      = startLine == null ? 1 : Math.max(1, startLine);
        int          end        = endLine   == null ? totalLines : Math.min(totalLines, endLine);
        if (start > end && totalLines > 0) throw new RuntimeException("Invalid line range");

        List<String> selected = totalLines == 0 ? List.of() : lines.subList(start - 1, end);
        return Map.of(
                "path",       relativePath(resolved),
                "text",       String.join("\n", selected),
                "startLine",  start,
                "endLine",    totalLines == 0 ? 0 : end,
                "totalLines", totalLines
        );
    }

    private Object fsSearch(Map<String, Object> args) throws IOException {
        String  query     = requireString(args, "query").toLowerCase();
        String  pathArg   = String.valueOf(args.getOrDefault("path", ""));
        boolean inContent = Boolean.TRUE.equals(args.get("inContent"));
        Path    base      = pathArg.isBlank() ? workspaceRoot : resolve(pathArg);

        List<Map<String, Object>> matches = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(base)) {
            stream.filter(Files::isRegularFile).forEach(file -> {
                try {
                    String  rel         = relativePath(file);
                    boolean nameMatch   = rel.toLowerCase().contains(query);
                    boolean contentMatch = false;
                    if (inContent) {
                        contentMatch = Files.readString(file, StandardCharsets.UTF_8)
                                           .toLowerCase().contains(query);
                    }
                    if (nameMatch || contentMatch) {
                        matches.add(Map.of(
                                "path",         rel,
                                "nameMatch",    nameMatch,
                                "contentMatch", contentMatch));
                    }
                } catch (IOException ignored) {}
            });
        }
        return Map.of("matches", matches);
    }

    private Object fsWrite(Map<String, Object> args) throws IOException {
        String path      = requireString(args, "path");
        String content   = requireString(args, "content");
        String operation = requireString(args, "operation");
        String action    = String.valueOf(args.getOrDefault("action", "overwrite"));
        Path   resolved  = resolve(path);
        Files.createDirectories(resolved.getParent());

        if ("create".equals(operation) || "overwrite".equals(action)) {
            Files.writeString(resolved, content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        } else if ("update".equals(operation) && "insert_after".equals(action)) {
            String prefix = Files.exists(resolved) && Files.size(resolved) > 0 ? System.lineSeparator() : "";
            Files.writeString(resolved, prefix + content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } else {
            throw new RuntimeException("Unsupported fs_write combination: operation=" + operation + " action=" + action);
        }
        return Map.of("path", relativePath(resolved), "bytes", Files.size(resolved), "status", "ok");
    }

    private Object fsManage(Map<String, Object> args) throws IOException {
        String operation = requireString(args, "operation");
        Path   resolved  = resolve(requireString(args, "path"));
        boolean recursive = Boolean.TRUE.equals(args.get("recursive"));

        return switch (operation) {
            case "mkdir" -> {
                if (recursive) Files.createDirectories(resolved);
                else           Files.createDirectory(resolved);
                yield Map.of("path", relativePath(resolved), "status", "created");
            }
            case "delete" -> {
                Files.deleteIfExists(resolved);
                yield Map.of("path", relativePath(resolved), "status", "deleted");
            }
            default -> throw new RuntimeException("Unsupported fs_manage operation: " + operation);
        };
    }

    // -----------------------------------------------------------------------
    // Utilities
    // -----------------------------------------------------------------------

    private Map<String, Object> entryDetails(Path path, boolean details) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("name", path.getFileName().toString());
        entry.put("path", relativePath(path));
        entry.put("kind", Files.isDirectory(path) ? "directory" : "file");
        if (details && Files.isRegularFile(path)) {
            try {
                entry.put("lineCount", Files.readAllLines(path, StandardCharsets.UTF_8).size());
                entry.put("size", Files.size(path));
            } catch (IOException ignored) {}
        }
        return entry;
    }

    /** Resolves a workspace-relative path, rejecting traversal attempts. */
    private Path resolve(String rawPath) {
        Path resolved = workspaceRoot.resolve(rawPath).normalize();
        if (!resolved.startsWith(workspaceRoot)) {
            throw new RuntimeException("Path escapes workspace root: " + rawPath);
        }
        return resolved;
    }

    private String relativePath(Path path) {
        return workspaceRoot.relativize(path.normalize()).toString().replace('\\', '/');
    }

    private static String requireString(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new RuntimeException("Missing or invalid string field: " + key);
        }
        return text;
    }

    private static Integer asInteger(Object value) {
        return value instanceof Number n ? n.intValue() : null;
    }
}
