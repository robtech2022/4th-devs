package com.exercise03;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ApiClient {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private final Config config;

    public ApiClient(Config config) {
        this.config = config;
    }

    public JsonNode chat(String model,
                         List<Object> messages,
                         List<Map<String, Object>> tools,
                         String toolChoice,
                         String systemPrompt) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", config.resolveModelForProvider(model));
        
        // Add system message if provided
        List<Object> fullMessages = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            fullMessages.add(Map.of("role", "system", "content", systemPrompt));
        }
        fullMessages.addAll(messages);
        
        body.put("messages", fullMessages);

        if (tools != null && !tools.isEmpty()) {
            body.put("tools", tools);
            body.put("tool_choice", toolChoice == null ? "auto" : toolChoice);
        }

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Authorization", "Bearer " + config.apiKey());
        headers.putAll(config.extraHeaders());

        JsonNode response = fetchJson(config.endpoint(), body, headers);
        JsonNode errorNode = response.path("error");
        if (!errorNode.isMissingNode() && !errorNode.isNull()) {
            String message = errorNode.path("message").asText("API error");
            throw new RuntimeException(message);
        }

        return response;
    }

    public List<ToolCall> extractToolCalls(JsonNode response) {
        List<ToolCall> calls = new ArrayList<>();
        JsonNode message = response.path("choices").path(0).path("message");
        JsonNode toolCalls = message.path("tool_calls");
        
        if (!toolCalls.isArray()) {
            return calls;
        }

        for (JsonNode toolCall : toolCalls) {
            String id = toolCall.path("id").asText("");
            String name = toolCall.path("function").path("name").asText("");
            String args = toolCall.path("function").path("arguments").asText("{}");
            
            Map<String, Object> raw = MAPPER.convertValue(toolCall, new TypeReference<>() {});
            calls.add(new ToolCall(id, name, args, raw));
        }

        return calls;
    }

    public String extractText(JsonNode response) {
        JsonNode message = response.path("choices").path(0).path("message");
        String content = message.path("content").asText("");
        
        if (content != null && !content.isBlank()) {
            return content;
        }

        return null;
    }

    public static String pretty(Object value) throws Exception {
        return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value);
    }

    public static JsonNode parseJson(String raw) throws Exception {
        return MAPPER.readTree(raw == null || raw.isBlank() ? "{}" : raw);
    }

    public static JsonNode fetchJson(String url,
                                     Map<String, Object> payload,
                                     Map<String, String> headers) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url));

        for (Map.Entry<String, String> header : headers.entrySet()) {
            builder.header(header.getKey(), header.getValue());
        }

        String body = MAPPER.writeValueAsString(payload == null ? Map.of() : payload);
        builder.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));

        HttpResponse<String> response = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        String raw = response.body() == null ? "" : response.body();

        JsonNode data;
        try {
            data = MAPPER.readTree(raw);
        } catch (Exception ex) {
            throw new RuntimeException("Non-JSON response: " + raw);
        }

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String message = data.path("error").path("message").asText("Request failed");
            throw new RuntimeException(message + " (status: " + response.statusCode() + ")");
        }

        return data;
    }

    public record ToolCall(String callId, String name, String argumentsJson, Map<String, Object> raw) {
    }
}
