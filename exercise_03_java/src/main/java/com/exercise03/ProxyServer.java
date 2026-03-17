package com.exercise03;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

public final class ProxyServer {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DEFAULT_MODEL = "gpt-4o-mini";
    
    private final SessionManager sessionManager;
    private final Executor executor;
    private final String model;
    private final int port;

    public ProxyServer(SessionManager sessionManager, Executor executor, String model, int port) {
        this.sessionManager = sessionManager;
        this.executor = executor;
        this.model = model;
        this.port = port;
    }

    public void start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        
        server.createContext("/", this::handleRequest);
        server.createContext("/health", this::handleHealth);
        
        server.setExecutor(null); // Use default executor
        server.start();
        
        System.out.println("🚀 Proxy server started on port " + port);
        System.out.println("📡 Endpoint: http://localhost:" + port + "/");
        System.out.println("💚 Health check: http://localhost:" + port + "/health");
        System.out.println("\nWaiting for requests...\n");
    }

    private void handleHealth(HttpExchange exchange) throws IOException {
        String response = "{\"status\":\"ok\"}";
        sendJsonResponse(exchange, 200, response);
    }

    private void handleRequest(HttpExchange exchange) throws IOException {
        // Only accept POST requests
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJsonResponse(exchange, 405, "{\"error\":\"Method not allowed. Use POST.\"}");
            return;
        }

        try {
            // Read request body
            String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            System.out.println("\n📨 Incoming request: " + requestBody);
            
            // Parse JSON
            JsonNode requestJson = MAPPER.readTree(requestBody);
            String sessionId = requestJson.path("sessionID").asText("");
            String msg = requestJson.path("msg").asText("");

            if (sessionId.isBlank()) {
                sendJsonResponse(exchange, 400, "{\"error\":\"sessionID is required\"}");
                return;
            }

            if (msg.isBlank()) {
                sendJsonResponse(exchange, 400, "{\"error\":\"msg is required\"}");
                return;
            }

            System.out.println("📝 Session: " + sessionId);
            System.out.println("💬 Message: " + msg);

            // Get session history
            List<Map<String, Object>> history = sessionManager.getHistory(sessionId);

            // Process message with LLM
            String responseText = executor.processMessage(history, msg, model);

            // Save updated history
            sessionManager.addMessage(sessionId, Map.of("role", "user", "content", msg));
            sessionManager.addMessage(sessionId, Map.of("role", "assistant", "content", responseText));

            System.out.println("✅ Response: " + responseText);

            // Send response
            String responseJson = MAPPER.writeValueAsString(Map.of("msg", responseText));
            sendJsonResponse(exchange, 200, responseJson);

        } catch (Exception e) {
            System.err.println("❌ Error processing request: " + e.getMessage());
            e.printStackTrace();
            
            String errorResponse = MAPPER.writeValueAsString(
                Map.of("error", "Internal server error: " + e.getMessage())
            );
            sendJsonResponse(exchange, 500, errorResponse);
        }
    }

    private void sendJsonResponse(HttpExchange exchange, int statusCode, String jsonResponse) throws IOException {
        byte[] bytes = jsonResponse.getBytes(StandardCharsets.UTF_8);
        
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
