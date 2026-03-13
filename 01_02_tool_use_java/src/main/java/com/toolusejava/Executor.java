package com.toolusejava;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public final class Executor {
    private static final int MAX_TOOL_ROUNDS = 10;

    private final ApiClient api;
    private final ToolHandlers handlers;

    public Executor(ApiClient api, ToolHandlers handlers) {
        this.api = api;
        this.handlers = handlers;
    }

    public String processQuery(String query,
                               String model,
                               List<Map<String, Object>> tools,
                               String instructions) throws Exception {
        logQuery(query);

        List<Object> conversation = new ArrayList<>();
        conversation.add(Map.of("role", "user", "content", query));

        for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
            JsonNode response = api.chat(model, conversation, tools, "auto", instructions);
            System.out.println("Response: " + response);
            
            List<ApiClient.ToolCall> toolCalls = api.extractToolCalls(response);

            if (toolCalls.isEmpty()) {
                String text = api.extractText(response);
                if (text == null || text.isBlank()) {
                    text = "No response";
                }
                logResult(text);
                return text;
            }

            List<Map<String, Object>> toolResults = executeToolCalls(toolCalls);

            List<Object> nextConversation = new ArrayList<>(conversation);
            for (ApiClient.ToolCall call : toolCalls) {
                nextConversation.add(call.raw());
            }
            nextConversation.addAll(toolResults);
            conversation = nextConversation;
        }

        logResult("Max tool rounds reached");
        return "Max tool rounds reached";
    }

    private List<Map<String, Object>> executeToolCalls(List<ApiClient.ToolCall> toolCalls) {
        System.out.println("\nTool calls: " + toolCalls.size());

        List<CompletableFuture<Map<String, Object>>> futures = toolCalls.stream()
                .map(call -> CompletableFuture.supplyAsync(() -> {
                    try {
                        JsonNode args = ApiClient.parseJson(call.argumentsJson());
                        System.out.println("  -> " + call.name() + "(" + args + ")");

                        Object result = handlers.execute(call.name(), args);
                        System.out.println("    + Success");

                        Map<String, Object> out = new LinkedHashMap<>();
                        out.put("type", "function_call_output");
                        out.put("call_id", call.callId());
                        out.put("output", ApiClient.pretty(result));
                        return out;
                    } catch (Exception ex) {
                        System.out.println("    x Error: " + ex.getMessage());
                        Map<String, Object> out = new LinkedHashMap<>();
                        out.put("type", "function_call_output");
                        out.put("call_id", call.callId());
                        out.put("output", "{\"error\":\"" + escapeJson(ex.getMessage()) + "\"}");
                        return out;
                    }
                }))
                .toList();

        return futures.stream().map(CompletableFuture::join).toList();
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

    private static void logQuery(String query) {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("Query: " + query);
        System.out.println("=".repeat(60));
    }

    private static void logResult(String text) {
        System.out.println("\nA: " + text);
    }
}
