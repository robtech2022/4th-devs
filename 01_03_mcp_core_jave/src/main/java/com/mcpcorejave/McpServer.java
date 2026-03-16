package com.mcpcorejave;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class McpServer {
    private final long startTimeMs = System.currentTimeMillis();
    private int requestCount = 0;

    private final List<Tool> tools;
    private final List<Resource> resources;
    private final List<Prompt> prompts;

    public McpServer() {
        this.tools = List.of(summarizeTool(), calculateTool());
        this.resources = List.of(projectConfigResource(), runtimeStatsResource());
        this.prompts = List.of(codeReviewPrompt());
    }

    public List<ToolMeta> listTools() {
        return tools.stream().map(tool -> new ToolMeta(tool.name(), tool.description())).toList();
    }

    public ToolResult callTool(String name, Map<String, Object> args, RequestBridge bridge) {
        Tool tool = tools.stream()
                .filter(t -> t.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Unknown tool: " + name));

        try {
            return tool.executor().execute(args == null ? Map.of() : args, bridge);
        } catch (Exception ex) {
            return new ToolResult("Error: " + ex.getMessage(), true);
        }
    }

    public List<ResourceMeta> listResources() {
        return resources.stream()
                .map(r -> new ResourceMeta(r.uri(), r.name(), r.description()))
                .toList();
    }

    public ResourceContent readResource(String uri) {
        Resource resource = resources.stream()
                .filter(r -> r.uri().equals(uri))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Unknown resource: " + uri));
        return resource.handler().execute();
    }

    public List<PromptMeta> listPrompts() {
        return prompts.stream().map(p -> new PromptMeta(p.name(), p.description())).toList();
    }

    public PromptResult getPrompt(String name, Map<String, Object> args) {
        Prompt prompt = prompts.stream()
                .filter(p -> p.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Unknown prompt: " + name));

        return prompt.handler().execute(args == null ? Map.of() : args);
    }

    private Tool summarizeTool() {
        return new Tool(
                "summarize_with_confirmation",
                "Summarizes text after getting user confirmation. Demonstrates elicitation and sampling.",
                (args, bridge) -> {
                    String text = requireText(args.get("text"), "text");
                    int maxLength = ((Number) args.getOrDefault("maxLength", 50)).intValue();

                    try {
                        String preview = text.length() > 100 ? text.substring(0, 100) + "..." : text;
                        Map<String, Object> elicitation = bridge.sendRequest(
                                "elicitation/create",
                                Map.of(
                                        "mode", "form",
                                        "message", "Do you want to summarize this text?\n\n\"" + preview + "\"",
                                        "requestedSchema", Map.of(
                                                "type", "object",
                                                "properties", Map.of(
                                                        "confirm", Map.of("type", "boolean", "title", "Confirm", "default", true),
                                                        "style", Map.of("type", "string", "enum", List.of("concise", "detailed", "bullet-points"), "default", "concise")
                                                ),
                                                "required", List.of("confirm")
                                        )
                                )
                        );

                        String action = String.valueOf(elicitation.getOrDefault("action", "decline"));
                        @SuppressWarnings("unchecked")
                        Map<String, Object> content = (Map<String, Object>) elicitation.getOrDefault("content", Map.of());
                        boolean confirm = Boolean.TRUE.equals(content.get("confirm"));

                        if (!"accept".equals(action) || !confirm) {
                            return new ToolResult("Summarization cancelled by user.", false);
                        }

                        String style = String.valueOf(content.getOrDefault("style", "concise"));

                        Map<String, Object> sampling = bridge.sendRequest(
                                "sampling/createMessage",
                                Map.of(
                                        "messages", List.of(Map.of(
                                                "role", "user",
                                                "content", Map.of(
                                                        "type", "text",
                                                        "text", "Summarize in a " + style + " style. Max " + maxLength + " words.\n\nText: " + text
                                                )
                                        )),
                                        "maxTokens", 200
                                )
                        );

                        @SuppressWarnings("unchecked")
                        Map<String, Object> samplingContent = (Map<String, Object>) sampling.getOrDefault("content", Map.of());
                        String summaryText = String.valueOf(samplingContent.getOrDefault("text", ""));

                        return new ToolResult("Summary (" + style + " style):\n\n" + summaryText, false);
                    } catch (Exception ex) {
                        return new ToolResult("Error: " + ex.getMessage() + ". Elicitation/sampling may not be supported by the client.", true);
                    }
                }
        );
    }

    private Tool calculateTool() {
        return new Tool(
                "calculate",
                "Performs basic arithmetic operations",
                (args, bridge) -> {
                    String operation = requireText(args.get("operation"), "operation");
                    double a = requireNumber(args.get("a"), "a");
                    double b = requireNumber(args.get("b"), "b");

                    Object result = switch (operation) {
                        case "add" -> a + b;
                        case "subtract" -> a - b;
                        case "multiply" -> a * b;
                        case "divide" -> b == 0 ? "Error: Division by zero" : a / b;
                        default -> throw new RuntimeException("Unsupported operation: " + operation);
                    };

                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("operation", operation);
                    out.put("a", a);
                    out.put("b", b);
                    out.put("result", result);

                    return new ToolResult(Json.json(out), false);
                }
        );
    }

    private Resource projectConfigResource() {
        return new Resource(
                "config://project",
                "Project Configuration",
                "Current project settings",
                () -> new ResourceContent(
                        "config://project",
                        "application/json",
                        Json.pretty(Map.of(
                                "name", "mcp-core-demo",
                                "version", "1.0.0",
                                "features", List.of("tools", "resources", "prompts", "elicitation", "sampling")
                        ))
                )
        );
    }

    private Resource runtimeStatsResource() {
        return new Resource(
                "data://stats",
                "Runtime Statistics",
                "Dynamic server stats",
            () -> {
                requestCount++;
                return new ResourceContent(
                    "data://stats",
                    "application/json",
                    Json.pretty(Map.of(
                        "uptime_seconds", Math.floorDiv(System.currentTimeMillis() - startTimeMs, 1000),
                        "request_count", requestCount,
                        "timestamp", Instant.now().toString()
                    ))
                );
            }
        );
    }

    private Prompt codeReviewPrompt() {
        return new Prompt(
                "code-review",
                "Template for code review requests",
                args -> {
                    String code = requireText(args.get("code"), "code");
                    String language = String.valueOf(args.getOrDefault("language", "unknown"));
                    String focus = String.valueOf(args.getOrDefault("focus", "all"));

                    String focusText = switch (focus) {
                        case "security" -> "Focus on security vulnerabilities and input validation.";
                        case "performance" -> "Focus on performance and optimization.";
                        case "readability" -> "Focus on code clarity and maintainability.";
                        default -> "Review for security, performance, and readability.";
                    };

                    String messageText = "Review this " + language + " code.\n\n"
                            + focusText + "\n\n"
                            + "```" + language + "\n"
                            + code + "\n"
                            + "```";

                    return new PromptResult(List.of(new PromptMessage("user", messageText)));
                }
        );
    }

    private static String requireText(Object value, String fieldName) {
        if (!(value instanceof String text) || text.isBlank()) {
            throw new RuntimeException("\"" + fieldName + "\" must be a non-empty string.");
        }
        return text.trim();
    }

    private static double requireNumber(Object value, String fieldName) {
        if (!(value instanceof Number number)) {
            throw new RuntimeException("\"" + fieldName + "\" must be a number.");
        }
        return number.doubleValue();
    }

    public interface RequestBridge {
        Map<String, Object> sendRequest(String method, Map<String, Object> params) throws Exception;
    }

    @FunctionalInterface
    public interface ToolExecutor {
        ToolResult execute(Map<String, Object> args, RequestBridge bridge) throws Exception;
    }

    @FunctionalInterface
    public interface ResourceHandler {
        ResourceContent execute();
    }

    @FunctionalInterface
    public interface PromptHandler {
        PromptResult execute(Map<String, Object> args);
    }

    public record Tool(String name, String description, ToolExecutor executor) {
    }

    public record Resource(String uri, String name, String description, ResourceHandler handler) {
    }

    public record Prompt(String name, String description, PromptHandler handler) {
    }

    public record ToolMeta(String name, String description) {
    }

    public record ResourceMeta(String uri, String name, String description) {
    }

    public record PromptMeta(String name, String description) {
    }

    public record ResourceContent(String uri, String mimeType, String text) {
    }

    public record PromptMessage(String role, String text) {
    }

    public record PromptResult(List<PromptMessage> messages) {
    }

    public record ToolResult(String text, boolean isError) {
    }
}
