package com.mcpnativejava.mcp;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

public final class McpServer {
    private final List<McpTool> tools;
    private final Random random = new Random();

    public McpServer() {
        this.tools = List.of(weatherTool(), timeTool());
    }

    public List<McpTool> listTools() {
        return new ArrayList<>(tools);
    }

    public Object callTool(String name, JsonNode args) {
        McpTool tool = tools.stream()
                .filter(t -> t.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Unknown MCP tool: " + name));

        return tool.execute(args);
    }

    private McpTool weatherTool() {
        return new McpTool(
                "get_weather",
                "Get current weather for a city",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "city", Map.of("type", "string", "description", "City name")
                        ),
                        "required", List.of("city"),
                        "additionalProperties", false
                ),
                args -> {
                    String city = requireText(args.path("city").asText(null), "city");
                    List<String> conditions = List.of("sunny", "cloudy", "rainy", "snowy");
                    String condition = conditions.get(random.nextInt(conditions.size()));
                    int temp = random.nextInt(40) - 5;
                    return Map.of("city", city, "condition", condition, "temperature", temp + "°C");
                }
        );
    }

    private McpTool timeTool() {
        return new McpTool(
                "get_time",
                "Get current time in a specified timezone",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "timezone", Map.of("type", "string", "description", "Timezone (e.g., 'UTC', 'Europe/London')")
                        ),
                        "required", List.of("timezone"),
                        "additionalProperties", false
                ),
                args -> {
                    String timezone = requireText(args.path("timezone").asText(null), "timezone");
                    try {
                        String time = ZonedDateTime.now(ZoneId.of(timezone)).toString();
                        return Map.of("timezone", timezone, "time", time);
                    } catch (DateTimeException ex) {
                        return Map.of("error", "Invalid timezone: " + timezone);
                    }
                }
        );
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new RuntimeException("\"" + fieldName + "\" must be a non-empty string.");
        }
        return value.trim();
    }

    public record McpTool(String name,
                          String description,
                          Map<String, Object> inputSchema,
                          McpExecutor executor) {
        public Object execute(JsonNode args) {
            return executor.execute(args);
        }
    }

    @FunctionalInterface
    public interface McpExecutor {
        Object execute(JsonNode args);
    }
}