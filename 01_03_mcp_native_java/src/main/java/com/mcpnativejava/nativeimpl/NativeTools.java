package com.mcpnativejava.nativeimpl;

import com.fasterxml.jackson.databind.JsonNode;
import com.mcpnativejava.ToolDefinitionFactory;

import java.util.List;
import java.util.Map;

public final class NativeTools {
    private NativeTools() {
    }

    public static List<Map<String, Object>> definitions() {
        return List.of(
                ToolDefinitionFactory.functionTool(
                        "calculate",
                        "Perform a basic math calculation",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "operation", Map.of(
                                                "type", "string",
                                                "enum", List.of("add", "subtract", "multiply", "divide"),
                                                "description", "The math operation to perform"
                                        ),
                                        "a", Map.of("type", "number", "description", "First operand"),
                                        "b", Map.of("type", "number", "description", "Second operand")
                                ),
                                "required", List.of("operation", "a", "b"),
                                "additionalProperties", false
                        )
                ),
                ToolDefinitionFactory.functionTool(
                        "uppercase",
                        "Convert text to uppercase",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "text", Map.of("type", "string", "description", "Text to convert")
                                ),
                                "required", List.of("text"),
                                "additionalProperties", false
                        )
                )
        );
    }

    public static Object calculate(JsonNode args) {
        String operation = requireText(args.path("operation").asText(null), "operation");
        double a = requireNumber(args.path("a"), "a");
        double b = requireNumber(args.path("b"), "b");

        return switch (operation) {
            case "add" -> Map.of("result", a + b, "expression", a + " + " + b);
            case "subtract" -> Map.of("result", a - b, "expression", a + " - " + b);
            case "multiply" -> Map.of("result", a * b, "expression", a + " * " + b);
            case "divide" -> b == 0
                    ? Map.of("error", "Division by zero")
                    : Map.of("result", a / b, "expression", a + " / " + b);
            default -> throw new RuntimeException("Unsupported operation: " + operation);
        };
    }

    public static Object uppercase(JsonNode args) {
        String text = requireText(args.path("text").asText(null), "text");
        return Map.of("result", text.toUpperCase());
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new RuntimeException("\"" + fieldName + "\" must be a non-empty string.");
        }
        return value.trim();
    }

    private static double requireNumber(JsonNode node, String fieldName) {
        if (node == null || !node.isNumber()) {
            throw new RuntimeException("\"" + fieldName + "\" must be a number.");
        }
        return node.asDouble();
    }
}