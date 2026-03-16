package com.mcpcorejave;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

public final class Log {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Log() {
    }

    public static void heading(String title, String description) {
        System.out.println("\n=== " + title + " ===");
        if (description != null && !description.isBlank()) {
            System.out.println(description);
        }
    }

    public static void log(String label, Object data) {
        System.out.println("\n> " + label);
        if (data == null) {
            return;
        }
        try {
            if (data instanceof String text) {
                System.out.println("  " + text);
                return;
            }
            System.out.println(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(data));
        } catch (Exception ex) {
            System.out.println(String.valueOf(data));
        }
    }

    public static Object parseToolResult(McpServer.ToolResult result) {
        if (result.isError()) {
            throw new RuntimeException(result.text() == null ? "Tool call failed" : result.text());
        }

        try {
            return MAPPER.readValue(result.text(), new TypeReference<Object>() {
            });
        } catch (Exception ignored) {
            return result.text();
        }
    }

    public static void spawningServer(String serverName) {
        System.out.println("\nSpawning MCP server: " + serverName);
    }

    public static void connected() {
        System.out.println("Connected to MCP server");
    }

    public static void samplingRequest(int messagesCount, Integer maxTokens) {
        System.out.println("\nSampling request from server");
        System.out.println("  Messages: " + messagesCount + ", max tokens: " + (maxTokens == null ? "default" : maxTokens));
    }

    public static void samplingResponse(String text) {
        String shortText = text == null ? "" : text;
        if (shortText.length() > 80) {
            shortText = shortText.substring(0, 80) + "...";
        }
        System.out.println("  LLM responded: \"" + shortText + "\"");
    }

    public static void samplingError(Throwable cause) {
        System.err.println("  Sampling error: " + cause.getMessage());
    }

    public static void elicitationRequest(String mode) {
        System.out.println("\nElicitation request from server");
        System.out.println("  Mode: " + mode);
    }

    public static void autoAcceptedElicitation(Object content) {
        System.out.println("  Auto-accepted with: " + content);
    }
}
