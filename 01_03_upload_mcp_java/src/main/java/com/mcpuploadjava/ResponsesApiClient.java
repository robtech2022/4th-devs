package com.mcpuploadjava;

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

public final class ResponsesApiClient {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient   HTTP   = HttpClient.newHttpClient();

    private final Config config;
    private final Stats  stats;

    public ResponsesApiClient(Config config, Stats stats) {
        this.config = config;
        this.stats  = stats;
    }

    public JsonNode chat(List<Object> input,
                         List<Map<String, Object>> tools,
                         String toolChoice,
                         String instructions,
                         Integer maxOutputTokens) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", config.model());
        body.put("input", input);
        if (tools != null && !tools.isEmpty()) {
            body.put("tools", tools);
            body.put("tool_choice", toolChoice == null ? "auto" : toolChoice);
        }
        if (instructions != null && !instructions.isBlank()) {
            body.put("instructions", instructions);
        }
        if (maxOutputTokens != null) {
            body.put("max_output_tokens", maxOutputTokens);
        }

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Authorization", "Bearer " + config.apiKey());
        headers.putAll(config.extraHeaders());

        JsonNode data = fetchJson(config.endpoint(), body, headers);
        stats.recordUsage(data.path("usage"));
        return data;
    }

    public List<ToolCall> extractToolCalls(JsonNode response) {
        List<ToolCall> calls = new ArrayList<>();
        JsonNode output = response.path("output");
        if (!output.isArray()) return calls;
        for (JsonNode item : output) {
            if (!"function_call".equals(item.path("type").asText(""))) continue;
            Map<String, Object> raw = MAPPER.convertValue(item, new TypeReference<>() {});
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
        String direct = response.path("output_text").asText("");
        if (!direct.isBlank()) return direct;
        JsonNode output = response.path("output");
        if (!output.isArray()) return null;
        for (JsonNode item : output) {
            if (!"message".equals(item.path("type").asText(""))) continue;
            JsonNode content = item.path("content");
            if (!content.isArray()) continue;
            for (JsonNode part : content) {
                if (!"output_text".equals(part.path("type").asText(""))) continue;
                String text = part.path("text").asText("");
                if (!text.isBlank()) return text;
            }
        }
        return null;
    }

    public Log.JsonNodeUsage usage(JsonNode response) {
        JsonNode usage = response.path("usage");
        if (usage.isMissingNode() || usage.isNull()) return null;
        return new Log.JsonNodeUsage(
                usage.path("input_tokens").asInt(0),
                usage.path("output_tokens").asInt(0),
                usage.path("input_tokens_details").path("cached_tokens").asInt(0)
        );
    }

    private static JsonNode fetchJson(String url,
                                      Map<String, Object> payload,
                                      Map<String, String> headers) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url));
        for (Map.Entry<String, String> h : headers.entrySet()) {
            builder.header(h.getKey(), h.getValue());
        }
        String body = MAPPER.writeValueAsString(payload);
        builder.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));

        HttpResponse<String> response = HTTP.send(
                builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        String raw = response.body() == null ? "" : response.body();

        JsonNode data;
        try {
            data = MAPPER.readTree(raw);
        } catch (Exception ex) {
            throw new RuntimeException("Non-JSON response: " + raw);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String msg = data.path("error").path("message").asText("Request failed");
            throw new RuntimeException(msg + " (status: " + response.statusCode() + ")");
        }
        if (!data.path("error").isMissingNode() && !data.path("error").isNull()) {
            throw new RuntimeException(data.path("error").path("message").asText("API error"));
        }
        return data;
    }

    public record ToolCall(String callId, String name, String argumentsJson, Map<String, Object> raw) {}
}
