package com.mcpcorejave;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class McpClient {
    private final McpServer server;
    private final SamplingHandler samplingHandler;
    private final ElicitationHandler elicitationHandler;

    public McpClient(McpServer server,
                     SamplingHandler samplingHandler,
                     ElicitationHandler elicitationHandler) {
        this.server = server;
        this.samplingHandler = samplingHandler;
        this.elicitationHandler = elicitationHandler;

        Log.spawningServer("mcp-core-demo");
        Log.connected();
    }

    public List<McpServer.ToolMeta> listTools() {
        return server.listTools();
    }

    public McpServer.ToolResult callTool(String name, Map<String, Object> args) {
        McpServer.RequestBridge bridge = (method, params) -> switch (method) {
            case "sampling/createMessage" -> samplingHandler.handle(params);
            case "elicitation/create" -> elicitationHandler.handle(params);
            default -> throw new RuntimeException("Unsupported client method: " + method);
        };

        return server.callTool(name, args, bridge);
    }

    public List<McpServer.ResourceMeta> listResources() {
        return server.listResources();
    }

    public ResourceReadResult readResource(String uri) {
        McpServer.ResourceContent content = server.readResource(uri);
        return new ResourceReadResult(List.of(content));
    }

    public List<McpServer.PromptMeta> listPrompts() {
        return server.listPrompts();
    }

    public McpServer.PromptResult getPrompt(String name, Map<String, Object> args) {
        return server.getPrompt(name, args);
    }

    public record ResourceReadResult(List<McpServer.ResourceContent> contents) {
    }

    @FunctionalInterface
    public interface SamplingHandler {
        Map<String, Object> handle(Map<String, Object> requestParams) throws Exception;
    }

    @FunctionalInterface
    public interface ElicitationHandler {
        Map<String, Object> handle(Map<String, Object> requestParams);
    }

    public static SamplingHandler createSamplingHandler(ResponsesApiClient ai, String model) {
        return requestParams -> {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> messages = (List<Map<String, Object>>) requestParams.getOrDefault("messages", List.of());
            Integer maxTokens = (requestParams.get("maxTokens") instanceof Number number)
                    ? number.intValue()
                    : null;

            Log.samplingRequest(messages.size(), maxTokens);

            try {
                List<Map<String, Object>> input = messages.stream().map(msg -> {
                    String role = String.valueOf(msg.getOrDefault("role", "user"));
                    @SuppressWarnings("unchecked")
                    Map<String, Object> content = (Map<String, Object>) msg.getOrDefault("content", Map.of());
                    String text = String.valueOf(content.getOrDefault("text", ""));
                    return (Map<String, Object>) Map.of("role", (Object) role, "content", (Object) text);
                }).toList();

                String text = ai.completion(model, input, maxTokens == null ? 500 : maxTokens);
                Log.samplingResponse(text);

                Map<String, Object> out = new LinkedHashMap<>();
                out.put("role", "assistant");
                out.put("content", Map.of("type", "text", "text", text));
                out.put("model", model);
                return out;
            } catch (Exception ex) {
                Log.samplingError(ex);
                throw ex;
            }
        };
    }

    public static ElicitationHandler createElicitationHandler() {
        return requestParams -> {
            String mode = String.valueOf(requestParams.getOrDefault("mode", ""));
            Log.elicitationRequest(mode);

            if (!"form".equals(mode)) {
                return Map.of("action", "decline");
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> requestedSchema = (Map<String, Object>) requestParams.getOrDefault("requestedSchema", Map.of());
            @SuppressWarnings("unchecked")
            Map<String, Object> properties = (Map<String, Object>) requestedSchema.getOrDefault("properties", Map.of());

            Map<String, Object> content = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : properties.entrySet()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> prop = (Map<String, Object>) entry.getValue();
                Object inferred = inferDefault(prop);
                if (inferred != null) {
                    content.put(entry.getKey(), inferred);
                }
            }

            Log.autoAcceptedElicitation(content);
            return Map.of("action", "accept", "content", content);
        };
    }

    private static Object inferDefault(Map<String, Object> prop) {
        if (prop.containsKey("default")) {
            return prop.get("default");
        }

        if ("boolean".equals(prop.get("type"))) {
            return true;
        }

        Object enumValue = prop.get("enum");
        if (enumValue instanceof List<?> list && !list.isEmpty()) {
            return list.getFirst();
        }

        return null;
    }
}
