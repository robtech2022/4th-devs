package com.mcptranslatorjava;

import com.fasterxml.jackson.databind.JsonNode;
import com.mcptranslatorjava.files.FilesMcpClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Agent {
    private static final int MAX_STEPS = 80;

    private final ResponsesApiClient api;
    private final Config config;

    public Agent(ResponsesApiClient api, Config config) {
        this.api = api;
        this.config = config;
    }

    public Result run(String query, FilesMcpClient mcpClient, List<Map<String, Object>> mcpTools) throws Exception {
        List<Object> messages = new ArrayList<>();
        messages.add(Map.of("role", "user", "content", query));
        List<Map<String, Object>> history = new ArrayList<>();

        Log.query(query);

        for (int step = 1; step <= MAX_STEPS; step++) {
            Log.api("Step " + step, messages.size());
            JsonNode response = api.chat(messages, mcpTools, "auto", config.instructions(), config.maxOutputTokens());
            Log.apiDone(api.usage(response));

            List<ResponsesApiClient.ToolCall> toolCalls = api.extractToolCalls(response);
            if (toolCalls.isEmpty()) {
                String text = api.extractText(response);
                if (text == null || text.isBlank()) {
                    text = "No response";
                }
                Log.response(text);
                return new Result(text, history);
            }

            JsonNode output = response.path("output");
            if (output.isArray()) {
                for (JsonNode item : output) {
                    messages.add(Json.convert(item, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {}));
                }
            }

            for (ResponsesApiClient.ToolCall toolCall : toolCalls) {
                history.add(Map.of(
                        "name", toolCall.name(),
                        "arguments", Json.parseObject(toolCall.argumentsJson())
                ));
            }

            List<Map<String, Object>> results = runTools(mcpClient, toolCalls);
            messages.addAll(results);
        }

        throw new RuntimeException("Max steps (" + MAX_STEPS + ") reached");
    }

    private List<Map<String, Object>> runTools(FilesMcpClient mcpClient, List<ResponsesApiClient.ToolCall> toolCalls) {
        List<Map<String, Object>> outputs = new ArrayList<>();
        for (ResponsesApiClient.ToolCall toolCall : toolCalls) {
            outputs.add(runTool(mcpClient, toolCall));
        }
        return outputs;
    }

    private Map<String, Object> runTool(FilesMcpClient mcpClient, ResponsesApiClient.ToolCall toolCall) {
        JsonNode args = Json.parseTree(toolCall.argumentsJson());
        Map<String, Object> mappedArgs = Json.convert(args, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        Log.tool(toolCall.name(), mappedArgs);
        try {
            Object result = mcpClient.callTool(toolCall.name(), mappedArgs);
            String output = Json.stringify(result);
            Log.toolResult(toolCall.name(), true, output);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("type", "function_call_output");
            response.put("call_id", toolCall.callId());
            response.put("output", output);
            return response;
        } catch (Exception ex) {
            String output = Json.stringify(Map.of("error", ex.getMessage()));
            Log.toolResult(toolCall.name(), false, ex.getMessage());
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("type", "function_call_output");
            response.put("call_id", toolCall.callId());
            response.put("output", output);
            return response;
        }
    }

    public record Result(String response, List<Map<String, Object>> toolCalls) {
    }
}
