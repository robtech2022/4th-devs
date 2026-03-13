package com.toolusejava;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ToolDefinitions {
    private ToolDefinitions() {
    }

    public static List<Map<String, Object>> tools() {
        List<Map<String, Object>> tools = new ArrayList<>();

        tools.add(buildTool(
                "list_files",
                "List files and directories at a given path within the sandbox",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "path", Map.of(
                                        "type", "string",
                                        "description", "Relative path within sandbox. Use '.' for root directory."
                                )
                        ),
                        "required", List.of("path"),
                        "additionalProperties", false
                )
        ));

        tools.add(buildTool(
                "read_file",
                "Read the contents of a file",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "path", Map.of(
                                        "type", "string",
                                        "description", "Relative path to the file within sandbox"
                                )
                        ),
                        "required", List.of("path"),
                        "additionalProperties", false
                )
        ));

        tools.add(buildTool(
                "write_file",
                "Write content to a file (creates or overwrites)",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "path", Map.of("type", "string", "description", "Relative path to the file within sandbox"),
                                "content", Map.of("type", "string", "description", "Content to write to the file")
                        ),
                        "required", List.of("path", "content"),
                        "additionalProperties", false
                )
        ));

        tools.add(buildTool(
                "delete_file",
                "Delete a file",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "path", Map.of("type", "string", "description", "Relative path to the file to delete")
                        ),
                        "required", List.of("path"),
                        "additionalProperties", false
                )
        ));

        tools.add(buildTool(
                "create_directory",
                "Create a directory (and parent directories if needed)",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "path", Map.of("type", "string", "description", "Relative path for the new directory")
                        ),
                        "required", List.of("path"),
                        "additionalProperties", false
                )
        ));

        tools.add(buildTool(
                "file_info",
                "Get metadata about a file or directory",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "path", Map.of("type", "string", "description", "Relative path to the file or directory")
                        ),
                        "required", List.of("path"),
                        "additionalProperties", false
                )
        ));

        return tools;
    }

    private static Map<String, Object> buildTool(String name, String description, Map<String, Object> parameters) {
        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("type", "function");
        tool.put("name", name);
        tool.put("description", description);
        tool.put("parameters", parameters);
        tool.put("strict", true);
        return tool;
    }
}
