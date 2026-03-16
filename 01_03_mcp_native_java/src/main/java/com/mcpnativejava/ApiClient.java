package com.mcpnativejava;

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
                         List<Object> input,
                         List<Map<String, Object>> tools,
                         String toolChoice,
                         String instructions) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", config.resolveModelForProvider(model));
        body.put("input", input);

        if (tools != null && !tools.isEmpty()) {
            body.put("tools", tools);
            body.put("tool_choice", toolChoice == null ? "auto" : toolChoice);
            body.put("parallel_tool_calls", true);
        }
        if (instructions != null && !instructions.isBlank()) {
            body.put("instructions", instructions);
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
        JsonNode output = response.path("output");
        if (!output.isArray()) {
            return calls;
        }

        for (JsonNode item : output) {
            if (!"function_call".equals(item.path("type").asText(""))) {
                continue;
            }

            Map<String, Object> raw = MAPPER.convertValue(item, new TypeReference<>() {
            });
            calls.add(new ToolCall(
                    item.path("call_id").asText(""),
                    item.path("name").asText(""),
                    item.path("arguments").asText("{}"),
                    raw
            ));
        }

        return calls;
    }

    public String extractText(JsonNode response) {
        String outputText = response.path("output_text").asText("");
        if (!outputText.isBlank()) {
            return outputText;
        }

        JsonNode output = response.path("output");
        if (output.isArray()) {
            for (JsonNode item : output) {
                if (!"message".equals(item.path("type").asText(""))) {
                    continue;
                }

                JsonNode content = item.path("content");
                if (!content.isArray()) {
                    continue;
                }

                for (JsonNode part : content) {
                    if (!"output_text".equals(part.path("type").asText(""))) {
                        continue;
                    }

                    String text = part.path("text").asText("");
                    if (!text.isBlank()) {
                        return text;
                    }
                }
            }
        }

        return null;
    }

    public static String pretty(Object value) throws Exception {
        return MAPPER.writeValueAsString(value);
    }

    public static JsonNode parseJson(String raw) throws Exception {
        return MAPPER.readTree(raw == null || raw.isBlank() ? "{}" : raw);
    }

    private static JsonNode fetchJson(String url,
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