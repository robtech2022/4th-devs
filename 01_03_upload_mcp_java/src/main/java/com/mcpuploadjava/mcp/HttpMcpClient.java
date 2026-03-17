package com.mcpuploadjava.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mcpuploadjava.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MCP client that speaks the Streamable HTTP transport (JSON-RPC over HTTP POST).
 *
 * <p>Supports both {@code application/json} and {@code text/event-stream} responses.
 */
public final class HttpMcpClient implements McpClient {

    private static final ObjectMapper    MAPPER     = new ObjectMapper();
    private static final HttpClient      HTTP       = HttpClient.newHttpClient();
    private static final AtomicInteger   ID_COUNTER = new AtomicInteger(1);

    private final String serverName;
    private final String url;
    private String sessionId;

    /**
     * Creates and initialises an HTTP MCP client.
     * Sends {@code initialize} + {@code notifications/initialized} during construction.
     */
    public HttpMcpClient(String serverName, String url) throws Exception {
        this.serverName = serverName;
        this.url        = url;
        initialize();
    }

    // -----------------------------------------------------------------------
    // McpClient interface
    // -----------------------------------------------------------------------

    @Override
    public List<Map<String, Object>> listTools() throws Exception {
        Map<String, Object> body     = jsonRpc("tools/list", Map.of());
        HttpResponse<String> response = post(body, sessionId);
        JsonNode data                 = parseResponse(response);

        if (data.has("error")) {
            throw new RuntimeException("MCP tools/list failed: "
                    + data.path("error").path("message").asText("unknown"));
        }
        JsonNode toolsNode = data.path("result").path("tools");
        List<Map<String, Object>> tools = new ArrayList<>();
        if (toolsNode.isArray()) {
            for (JsonNode tool : toolsNode) {
                tools.add(MAPPER.convertValue(tool, new TypeReference<>() {}));
            }
        }
        return tools;
    }

    @Override
    public Object callTool(String name, Map<String, Object> args) throws Exception {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", name);
        params.put("arguments", args == null ? Map.of() : args);

        HttpResponse<String> response = post(jsonRpc("tools/call", params), sessionId);
        JsonNode data = parseResponse(response);

        if (data.has("error")) {
            throw new RuntimeException("MCP tool call failed (" + name + "): "
                    + data.path("error").path("message").asText("unknown"));
        }
        JsonNode content = data.path("result").path("content");
        if (content.isArray()) {
            for (JsonNode item : content) {
                if ("text".equals(item.path("type").asText(""))) {
                    String text = item.path("text").asText("");
                    try { return MAPPER.readTree(text); }
                    catch (Exception ignored) { return text; }
                }
            }
        }
        return MAPPER.convertValue(data.path("result"), Object.class);
    }

    @Override
    public void close() {
        // HTTP is stateless; nothing to release explicitly
    }

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    private void initialize() throws Exception {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("protocolVersion", "2024-11-05");
        params.put("capabilities", Map.of());
        params.put("clientInfo", Map.of("name", "mcp-upload-client", "version", "1.0.0"));

        HttpResponse<String> response = post(jsonRpc("initialize", params), null);
        response.headers().firstValue("mcp-session-id").ifPresent(id -> this.sessionId = id);

        JsonNode data = parseResponse(response);
        if (data.has("error")) {
            throw new RuntimeException("MCP initialize failed on " + serverName + ": "
                    + data.path("error").path("message").asText("unknown"));
        }
        Log.info("MCP session initialized: " + serverName
                + (sessionId != null ? " (session: " + sessionId + ")" : ""));

        // MCP spec requires sending notifications/initialized after a successful handshake
        Map<String, Object> notif = new LinkedHashMap<>();
        notif.put("jsonrpc", "2.0");
        notif.put("method", "notifications/initialized");
        notif.put("params", Map.of());
        try { post(notif, sessionId); } catch (Exception ignored) {}
    }

    private HttpResponse<String> post(Map<String, Object> body, String sid) throws Exception {
        String json = MAPPER.writeValueAsString(body);
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
        if (sid != null) builder.header("mcp-session-id", sid);
        return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private JsonNode parseResponse(HttpResponse<String> response) throws IOException {
        String contentType = response.headers().firstValue("content-type").orElse("");
        String body = response.body() == null ? "" : response.body();

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new RuntimeException("HTTP " + response.statusCode() + " from " + serverName + ": " + body);
        }
        if (contentType.contains("text/event-stream")) {
            return parseSse(body);
        }
        return MAPPER.readTree(body.isBlank() ? "{}" : body);
    }

    /** Parses the first JSON-RPC data event from an SSE body. */
    private JsonNode parseSse(String sseBody) throws IOException {
        BufferedReader reader = new BufferedReader(new StringReader(sseBody));
        String line;
        StringBuilder dataBuffer = new StringBuilder();

        while ((line = reader.readLine()) != null) {
            if (line.startsWith("data: ")) {
                dataBuffer.append(line.substring(6));
            } else if (line.isEmpty() && !dataBuffer.isEmpty()) {
                String data = dataBuffer.toString();
                dataBuffer = new StringBuilder();
                if (!"[DONE]".equals(data)) {
                    try { return MAPPER.readTree(data); } catch (Exception ignored) {}
                }
            }
        }
        return MAPPER.createObjectNode();
    }

    private static Map<String, Object> jsonRpc(String method, Map<String, Object> params) {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("jsonrpc", "2.0");
        req.put("id", ID_COUNTER.getAndIncrement());
        req.put("method", method);
        req.put("params", params);
        return req;
    }
}
