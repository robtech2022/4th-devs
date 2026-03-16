package com.mcpcorejave;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ResponsesApiClient {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private final Config config;

    public ResponsesApiClient(Config config) {
        this.config = config;
    }

    public String completion(String model, List<Map<String, Object>> input, Integer maxOutputTokens) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", config.resolveModelForProvider(model));
        body.put("input", input);
        if (maxOutputTokens != null) {
            body.put("max_output_tokens", maxOutputTokens);
        }

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Authorization", "Bearer " + config.apiKey());
        headers.putAll(config.extraHeaders());

        JsonNode data = fetchJson(config.endpoint(), body, headers);

        String directText = data.path("output_text").asText("").trim();
        if (!directText.isBlank()) {
            return directText;
        }

        JsonNode output = data.path("output");
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

                    String text = part.path("text").asText("").trim();
                    if (!text.isBlank()) {
                        return text;
                    }
                }
            }
        }

        throw new RuntimeException("Empty response");
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

        JsonNode errorNode = data.path("error");
        if (!errorNode.isMissingNode() && !errorNode.isNull()) {
            String message = errorNode.path("message").asText("API error");
            throw new RuntimeException(message);
        }

        return data;
    }
}
