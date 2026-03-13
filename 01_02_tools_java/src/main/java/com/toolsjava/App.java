package com.toolsjava;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class App {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private static final String OPENAI_ENDPOINT = "https://api.openai.com/v1/responses";
    private static final String OPENROUTER_ENDPOINT = "https://openrouter.ai/api/v1/responses";
    private static final int MAX_TOOL_STEPS = 50;

    public static void main(String[] args) throws Exception {
        Config cfg = loadConfig();

        String baseModel = "gpt-4.1-mini";
        boolean webSearch = true;

        // String query = "Execute these in parrarel. Send a short example email to student@example.com and Use web search to check the current weather in Krakow.";
        String query = "Send 2 short example emails to student@example.com.If multiple independent tasks are requested, emit all required function calls in the same response.";
        logQuestion(query);

        List<Object> conversation = new ArrayList<>();
        conversation.add(Map.of("role", "user", "content", query));
        System.out.println("[MAIN] conversation payload:");
        System.out.println(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(conversation));

        String answer = chat(conversation, cfg, baseModel, webSearch);
        
        logAnswer(answer);
    }

    private static String chat(List<Object> conversation, Config cfg, String model, boolean webSearch) throws Exception {
        List<Object> currentConversation = new ArrayList<>(conversation);
        int stepsRemaining = MAX_TOOL_STEPS;
        int step = 1;

        System.out.println("[CHAT] Starting loop: provider=" + cfg.provider()
                + ", model=" + resolveModelForProvider(cfg.provider(), model)
                + ", webSearch=" + webSearch
                + ", maxSteps=" + MAX_TOOL_STEPS);

        while (stepsRemaining > 0) {
            stepsRemaining -= 1;

            System.out.println("[CHAT] Step " + step + " begin | inputMessages=" + currentConversation.size()
                    + " | stepsRemainingAfterThis=" + stepsRemaining);
            System.out.println("[CHAT] Step " + step + " currentConversation payload:");
            System.out.println(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(currentConversation));

            JsonNode response = requestResponse(currentConversation, cfg, model, webSearch);
            // System.out.println("[CHAT] Step " + step + " raw response:");
            // System.out.println(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(response));


            List<ToolCall> toolCalls = getToolCalls(response);
            // System.out.println("[CHAT] Step " + step + " toolCalls payload:");
            // System.out.println(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(toolCalls));

            System.out.println("[CHAT] Step " + step + " response received | toolCalls=" + toolCalls.size());

            if (toolCalls.isEmpty()) {
                System.out.println("[CHAT] Step " + step + " no tool calls, extracting final text");
                return getFinalText(response);
            }

            List<Object> nextConversation = new ArrayList<>(currentConversation);
            for (ToolCall call : toolCalls) {
                System.out.println("[CHAT] Step " + step + " model requested tool: " + call.name());
                nextConversation.add(call.raw());
            }

            for (ToolCall call : toolCalls) {
                Map<String, Object> result = executeToolCall(call);
                nextConversation.add(result);
            }

            currentConversation = nextConversation;
            System.out.println("[CHAT] Step " + step + " complete | nextInputMessages=" + currentConversation.size());
            step += 1;
        }

        System.out.println("[CHAT] Stopped after reaching max tool steps without final response");
        throw new RuntimeException("Tool calling did not finish within " + MAX_TOOL_STEPS + " steps.");
    }

    private static JsonNode requestResponse(List<Object> input, Config cfg, String model, boolean webSearch) throws Exception {
        Map<String, Object> body = buildResponsesRequest(model, input, buildTools(), webSearch, cfg.provider());

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Authorization", "Bearer " + cfg.apiKey());
        headers.putAll(cfg.extraHeaders());

        JsonNode data = fetchJson("POST", cfg.endpoint(), body, headers);
        JsonNode errorNode = data.path("error");
        if (!errorNode.isMissingNode() && !errorNode.isNull()) {
            throw new RuntimeException(buildApiErrorMessage(errorNode, cfg.provider(), cfg.endpoint()));
        }
        return data;
    }

    private static String buildApiErrorMessage(JsonNode errorNode, String provider, String endpoint) {
        if (errorNode == null || errorNode.isNull() || errorNode.isMissingNode()) {
            return "API error (provider=" + provider + ", endpoint=" + endpoint + ")";
        }

        String message = firstNonBlank(
                errorNode.path("message").asText(""),
                errorNode.path("detail").asText(""),
                errorNode.path("error_description").asText(""),
                errorNode.path("title").asText("")
        );

        String type = errorNode.path("type").asText("");
        String code = errorNode.path("code").asText("");
        String param = errorNode.path("param").asText("");

        String payload;
        try {
            payload = MAPPER.writeValueAsString(errorNode);
        } catch (Exception ignored) {
            payload = errorNode.toString();
        }

        StringBuilder out = new StringBuilder();
        out.append(message.isBlank() ? "API error" : message);

        List<String> meta = new ArrayList<>();
        if (!provider.isBlank()) {
            meta.add("provider=" + provider);
        }
        if (!type.isBlank()) {
            meta.add("type=" + type);
        }
        if (!code.isBlank()) {
            meta.add("code=" + code);
        }
        if (!param.isBlank()) {
            meta.add("param=" + param);
        }

        if (!meta.isEmpty()) {
            out.append(" (").append(String.join(", ", meta)).append(")");
        }
        out.append(" endpoint=").append(endpoint);
        out.append(" error=").append(payload);

        return out.toString();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static Map<String, Object> buildResponsesRequest(String model,
                                                             List<Object> input,
                                                             List<Map<String, Object>> tools,
                                                             boolean webSearch,
                                                             String provider) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", resolveModelForProvider(provider, model));
        request.put("input", input);
        request.put("tools", tools);

        if (!webSearch) {
            return request;
        }

        if ("openrouter".equals(provider)) {
            String currentModel = String.valueOf(request.get("model"));
            if (!currentModel.endsWith(":online")) {
                request.put("model", currentModel + ":online");
            }
            return request;
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> currentTools = (List<Map<String, Object>>) request.get("tools");
        boolean hasWebSearch = currentTools.stream()
                .anyMatch(t -> "web_search_preview".equals(String.valueOf(t.get("type"))));
        if (!hasWebSearch) {
            currentTools = new ArrayList<>(currentTools);
            currentTools.add(Map.of("type", "web_search_preview"));
            request.put("tools", currentTools);
        }

        return request;
    }

    private static String resolveModelForProvider(String provider, String model) {
        if (model == null || model.isBlank()) {
            throw new RuntimeException("Model must be a non-empty string");
        }

        if (!"openrouter".equals(provider) || model.contains("/")) {
            return model;
        }

        return model.startsWith("gpt-") ? "openai/" + model : model;
    }

    private static List<Map<String, Object>> buildTools() {
        List<Map<String, Object>> tools = new ArrayList<>();
       

        tools.add(buildTool(
                "send_email",
                "Send a short email message to a recipient",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "to", Map.of("type", "string", "description", "Recipient email address"),
                                "subject", Map.of("type", "string", "description", "Email subject"),
                                "body", Map.of("type", "string", "description", "Plain-text email body")
                        ),
                        "required", List.of("to", "subject", "body"),
                        "additionalProperties", false
                )
        ));

         tools.add(buildTool(
                "get_weather",
                "Get current weather for a given location",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "location", Map.of("type", "string", "description", "City name")
                        ),
                        "required", List.of("location"),
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

    private static List<ToolCall> getToolCalls(JsonNode response) {
        List<ToolCall> calls = new ArrayList<>();
        JsonNode output = response.path("output");
        if (!output.isArray()) {
            return calls;
        }

        for (JsonNode item : output) {
            if (!"function_call".equals(item.path("type").asText(""))) {
                continue;
            }

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

    private static String getFinalText(JsonNode response) {
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
                if (content.isArray() && !content.isEmpty()) {
                    String text = content.path(0).path("text").asText("");
                    if (!text.isBlank()) {
                        return text;
                    }
                }
            }
        }

        return "No response";
    }

    private static Map<String, Object> executeToolCall(ToolCall call) throws Exception {
        JsonNode args = MAPPER.readTree(call.argumentsJson().isBlank() ? "{}" : call.argumentsJson());

        logToolCall(call.name(), args);

        Map<String, Object> result;
        switch (call.name()) {
            case "get_weather" -> result = getWeather(args);
            case "send_email" -> result = sendEmail(args);
            default -> throw new RuntimeException("Unknown tool: " + call.name());
        }

        logToolResult(result);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", "function_call_output");
        out.put("call_id", call.callId());
        out.put("output", MAPPER.writeValueAsString(result));
        return out;
    }

    private static Map<String, Object> getWeather(JsonNode args) {
        String city = requireText(args.path("location").asText(null), "location");
        String normalizedCity = normalizeText(city);
        return switch (normalizedCity) {
            case "krakow" -> Map.of("temp", -2, "conditions", "snow");
            case "london" -> Map.of("temp", 8, "conditions", "rain");
            case "tokyo" -> Map.of("temp", 15, "conditions", "cloudy");
            default -> Map.of("temp", null, "conditions", "unknown");
        };
    }

    private static String normalizeText(String value) {
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return normalized.trim().toLowerCase(Locale.ROOT);
    }

    private static Map<String, Object> sendEmail(JsonNode args) {
        String to = requireText(args.path("to").asText(null), "to");
        String subject = requireText(args.path("subject").asText(null), "subject");
        String body = requireText(args.path("body").asText(null), "body");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("status", "sent");
        result.put("to", to);
        result.put("subject", subject);
        result.put("body", body);
        return result;
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new RuntimeException("\"" + fieldName + "\" must be a non-empty string.");
        }
        return value.trim();
    }

    private static JsonNode fetchJson(String method,
                                      String url,
                                      Map<String, Object> payload,
                                      Map<String, String> headers) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url));

        if (headers != null) {
            for (Map.Entry<String, String> header : headers.entrySet()) {
                builder.header(header.getKey(), header.getValue());
            }
        }

        if ("POST".equalsIgnoreCase(method)) {
            String body = MAPPER.writeValueAsString(payload == null ? Map.of() : payload);
            builder.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        } else {
            builder.GET();
        }

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

    private static Config loadConfig() throws IOException {
        String openAiKey = env("OPENAI_API_KEY");
        String openRouterKey = env("OPENROUTER_API_KEY");
        String requestedProvider = env("AI_PROVIDER").toLowerCase(Locale.ROOT).trim();

        boolean hasOpenAi = !openAiKey.isBlank();
        boolean hasOpenRouter = !openRouterKey.isBlank();

        if (!hasOpenAi && !hasOpenRouter) {
            throw new RuntimeException("No API key set. Add OPENAI_API_KEY or OPENROUTER_API_KEY");
        }

        Set<String> validProviders = Set.of("", "openai", "openrouter");
        if (!validProviders.contains(requestedProvider)) {
            throw new RuntimeException("AI_PROVIDER must be one of: openai, openrouter");
        }

        String provider;
        if (!requestedProvider.isBlank()) {
            provider = requestedProvider;
            if ("openai".equals(provider) && !hasOpenAi) {
                throw new RuntimeException("AI_PROVIDER=openai requires OPENAI_API_KEY");
            }
            if ("openrouter".equals(provider) && !hasOpenRouter) {
                throw new RuntimeException("AI_PROVIDER=openrouter requires OPENROUTER_API_KEY");
            }
        } else {
            provider = hasOpenAi ? "openai" : "openrouter";
        }

        String endpoint = "openrouter".equals(provider) ? OPENROUTER_ENDPOINT : OPENAI_ENDPOINT;
        String apiKey = "openrouter".equals(provider) ? openRouterKey : openAiKey;

        Map<String, String> extraHeaders = new LinkedHashMap<>();
        if ("openrouter".equals(provider)) {
            String referer = env("OPENROUTER_HTTP_REFERER");
            String appName = env("OPENROUTER_APP_NAME");
            if (!referer.isBlank()) {
                extraHeaders.put("HTTP-Referer", referer);
            }
            if (!appName.isBlank()) {
                extraHeaders.put("X-Title", appName);
            }
        }

        return new Config(provider, endpoint, apiKey, extraHeaders);
    }

    private static String env(String key) throws IOException {
        String envVal = System.getenv(key);
        if (envVal != null && !envVal.isBlank()) {
            return envVal.trim();
        }

        Path cwd = Path.of(".").toAbsolutePath().normalize();
        List<Path> candidates = List.of(
                cwd.resolve(".env"),
                cwd.resolve("4th-devs/.env"),
                cwd.resolve("../.env").normalize(),
                cwd.resolve("../../.env").normalize()
        );

        for (Path envPath : candidates) {
            if (!Files.exists(envPath)) {
                continue;
            }

            for (String line : Files.readAllLines(envPath, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.startsWith("#") || !trimmed.contains("=")) {
                    continue;
                }
                if (trimmed.startsWith(key + "=")) {
                    return trimmed.substring((key + "=").length()).trim();
                }
            }
        }

        return "";
    }

    private static void logQuestion(String text) {
        System.out.println("[USER] " + text);
        System.out.println();
    }

    private static void logToolCall(String name, JsonNode args) throws Exception {
        System.out.println("[TOOL] " + name);
        System.out.println("Arguments:");
        System.out.println(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(args));
    }

    private static void logToolResult(Map<String, Object> result) throws Exception {
        System.out.println("Result:");
        System.out.println(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(result));
        System.out.println();
    }

    private static void logAnswer(String text) {
        System.out.println("[ASSISTANT] " + text);
    }

    private record ToolCall(String callId, String name, String argumentsJson, Map<String, Object> raw) {
    }

    private record Config(String provider, String endpoint, String apiKey, Map<String, String> extraHeaders) {
    }
}
