package com.mcpnativejava;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Agent {
    private static final int MAX_TOOL_ROUNDS = 10;

    private final ApiClient api;
    private final String model;
    private final List<Map<String, Object>> tools;
    private final String instructions;
    private final Map<String, ToolHandler> handlers;

    public Agent(ApiClient api,
                 String model,
                 List<Map<String, Object>> tools,
                 String instructions,
                 Map<String, ToolHandler> handlers) {
        this.api = api;
        this.model = model;
        this.tools = tools;
        this.instructions = instructions;
        this.handlers = handlers;
    }

    public String processQuery(String query) throws Exception {
        Log.query(query);

        List<Object> conversation = new ArrayList<>();
        conversation.add(Map.of("role", "user", "content", query));

        for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
            JsonNode response = api.chat(model, conversation, tools, "auto", instructions);
            // Log.response(ApiClient.pretty(response));

            List<ApiClient.ToolCall> toolCalls = api.extractToolCalls(response);
            Log.response("toolCalls: " + ApiClient.pretty(toolCalls));

            if (toolCalls.isEmpty()) {
                String text = api.extractText(response);
                if (text == null || text.isBlank()) {
                    text = "No response";
                }
                Log.response(text);
                return text;
            }

            Log.toolCount(toolCalls.size());

            List<Map<String, Object>> toolResults = new ArrayList<>();
            for (ApiClient.ToolCall call : toolCalls) {
                toolResults.add(executeToolCall(call));
            }

            List<Object> nextConversation = new ArrayList<>(conversation);
            for (ApiClient.ToolCall call : toolCalls) {
                nextConversation.add(call.raw());
            }
            nextConversation.addAll(toolResults);
            conversation = nextConversation;
        }

        Log.response("Max tool rounds reached");
        return "Max tool rounds reached";
    }

    private Map<String, Object> executeToolCall(ApiClient.ToolCall call) {
        try {
            JsonNode args = ApiClient.parseJson(call.argumentsJson());
            ToolHandler handler = handlers.get(call.name());
            if (handler == null) {
                throw new RuntimeException("Unknown tool: " + call.name());
            }

            Log.toolCall(handler.label(), call.name(), args);

            Object result = handler.executor().execute(args);
            Log.toolResult(result);

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("type", "function_call_output");
            out.put("call_id", call.callId());
            out.put("output", ApiClient.pretty(result));
            return out;
        } catch (Exception ex) {
            Log.toolError(ex.getMessage());

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("type", "function_call_output");
            out.put("call_id", call.callId());
            out.put("output", "{\"error\":\"" + escapeJson(ex.getMessage()) + "\"}");
            return out;
        }
    }

    private static String escapeJson(String text) {
        if (text == null) {
            return "";
        }
        return text
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    public record ToolHandler(String label, Executor executor) {
    }

    @FunctionalInterface
    public interface Executor {
        Object execute(JsonNode args) throws Exception;
    }
}