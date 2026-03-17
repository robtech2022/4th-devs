package com.mcpuploadjava;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.mcpuploadjava.files.FileRefResolver;
import com.mcpuploadjava.mcp.McpClientManager;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agentic loop that orchestrates tool calls routed across multiple MCP servers.
 *
 * <p>Flow: user message → model → {tool calls → resolve {{file:path}} refs → MCP → results → model} → final answer
 */
public final class Agent {

    private static final int MAX_STEPS = 50;

    private final ResponsesApiClient api;
    private final Config config;

    public Agent(ResponsesApiClient api, Config config) {
        this.api    = api;
        this.config = config;
    }

    public Result run(String query,
                      McpClientManager mcpManager,
                      List<Map<String, Object>> mcpTools) throws Exception {
        List<Object> messages = new ArrayList<>();
        messages.add(Map.of("role", "user", "content", query));
        Log.query(query);

        // Workspace root is used by FileRefResolver to encode {{file:path}} refs
        Path workspaceRoot = mcpManager.filesWorkspaceRoot();

        for (int step = 1; step <= MAX_STEPS; step++) {
            Log.api("Step " + step, messages.size());
            JsonNode response = api.chat(messages, mcpTools, "auto",
                    config.instructions(), config.maxOutputTokens());
            Log.apiDone(api.usage(response));

            List<ResponsesApiClient.ToolCall> toolCalls = api.extractToolCalls(response);

            if (toolCalls.isEmpty()) {
                String text = api.extractText(response);
                if (text == null || text.isBlank()) text = "No response";
                Log.response(text);
                return new Result(text);
            }

            // Append function_call items from the model's response to history
            JsonNode output = response.path("output");
            if (output.isArray()) {
                for (JsonNode item : output) {
                    messages.add(Json.convert(item, new TypeReference<Map<String, Object>>() {}));
                }
            }

            List<Map<String, Object>> results = executeCalls(mcpManager, toolCalls, workspaceRoot);
            messages.addAll(results);
        }

        throw new RuntimeException("Tool loop did not finish within " + MAX_STEPS + " steps.");
    }

    // -----------------------------------------------------------------------

    private List<Map<String, Object>> executeCalls(McpClientManager mcpManager,
                                                   List<ResponsesApiClient.ToolCall> calls,
                                                   Path workspaceRoot) {
        List<Map<String, Object>> outputs = new ArrayList<>(calls.size());
        for (ResponsesApiClient.ToolCall call : calls) {
            outputs.add(executeCall(mcpManager, call, workspaceRoot));
        }
        return outputs;
    }

    private Map<String, Object> executeCall(McpClientManager mcpManager,
                                            ResponsesApiClient.ToolCall call,
                                            Path workspaceRoot) {
        JsonNode rawArgs = Json.parseTree(call.argumentsJson());
        Map<String, Object> args = Json.convert(rawArgs, new TypeReference<Map<String, Object>>() {});
        Log.tool(call.name(), args);

        String output;
        try {
            Map<String, Object> resolvedArgs = (workspaceRoot != null)
                    ? FileRefResolver.resolve(args, workspaceRoot)
                    : args;
            Object result = mcpManager.callTool(call.name(), resolvedArgs);
            output = Json.stringify(result);
            Log.toolResult(call.name(), true, output);
        } catch (Exception ex) {
            String msg = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
            output = Json.stringify(Map.of("error", msg));
            Log.toolResult(call.name(), false, msg);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("type",    "function_call_output");
        response.put("call_id", call.callId());
        response.put("output",  output);
        return response;
    }

    public record Result(String response) {}
}
