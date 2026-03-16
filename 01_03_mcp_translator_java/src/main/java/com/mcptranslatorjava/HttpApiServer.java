package com.mcptranslatorjava;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.mcptranslatorjava.files.FilesMcpClient;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

public final class HttpApiServer {
    private final Config config;
    private final Agent agent;
    private HttpServer server;

    public HttpApiServer(Config config, Agent agent) {
        this.config = config;
        this.agent = agent;
    }

    public HttpServer start(ContextSupplier contextSupplier) throws IOException {
        server = HttpServer.create(new InetSocketAddress(config.host(), config.port()), 0);
        server.createContext("/api/chat", new JsonHandler(exchange -> handleChat(exchange, contextSupplier)));
        server.createContext("/api/translate", new JsonHandler(exchange -> handleTranslate(exchange, contextSupplier)));
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();

        String publicHost = ("0.0.0.0".equals(config.host()) || "::".equals(config.host())) ? "localhost" : config.host();
        String baseUrl = "http://" + publicHost + ":" + config.port();
        Log.ready("Server listening on " + baseUrl);
        Log.info("Files in workspace/translate/ will be translated automatically.");
        Log.info("POST " + baseUrl + "/api/translate to translate ad hoc text.");
        return server;
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private void handleChat(HttpExchange exchange, ContextSupplier contextSupplier) throws IOException {
        if (handleOptionsOrMethod(exchange)) {
            return;
        }
        Context context = contextSupplier.get();
        if (context.mcpClient() == null) {
            sendJson(exchange, 503, Map.of("error", "MCP client not connected"));
            return;
        }
        try {
            Map<String, Object> body = readJsonBody(exchange);
            Object message = body.get("message");
            if (!(message instanceof String text) || text.isBlank()) {
                sendJson(exchange, 400, Map.of("error", "Message is required"));
                return;
            }
            Agent.Result result = agent.run(text, context.mcpClient(), context.mcpTools());
            sendJson(exchange, 200, Map.of("response", result.response(), "toolCalls", result.toolCalls()));
        } catch (Exception ex) {
            Log.error("Request error", ex.getMessage());
            sendJson(exchange, 500, Map.of("error", ex.getMessage()));
        }
    }

    private void handleTranslate(HttpExchange exchange, ContextSupplier contextSupplier) throws IOException {
        if (handleOptionsOrMethod(exchange)) {
            return;
        }
        Context context = contextSupplier.get();
        if (context.mcpClient() == null) {
            sendJson(exchange, 503, Map.of("error", "MCP client not connected"));
            return;
        }
        try {
            Map<String, Object> body = readJsonBody(exchange);
            Object textValue = body.get("text");
            if (!(textValue instanceof String text) || text.isBlank()) {
                sendJson(exchange, 400, Map.of("error", "Text is required"));
                return;
            }
            String query = "Translate the following text to English. Preserve tone, formatting, and nuances:\n\n" + text;
            Agent.Result result = agent.run(query, context.mcpClient(), context.mcpTools());
            sendJson(exchange, 200, Map.of("translation", result.response()));
        } catch (Exception ex) {
            Log.error("Request error", ex.getMessage());
            sendJson(exchange, 500, Map.of("error", ex.getMessage()));
        }
    }

    private boolean handleOptionsOrMethod(HttpExchange exchange) throws IOException {
        addCors(exchange);
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            return true;
        }
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, Map.of("error", "Method not allowed"));
            return true;
        }
        return false;
    }

    private Map<String, Object> readJsonBody(HttpExchange exchange) throws IOException {
        try (InputStream input = exchange.getRequestBody()) {
            String raw = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            if (raw.isBlank()) {
                return Map.of();
            }
            return Json.convert(Json.parseObject(raw), new com.fasterxml.jackson.core.type.TypeReference<>() {});
        } catch (Exception ex) {
            throw new IOException("Invalid JSON", ex);
        }
    }

    private void sendJson(HttpExchange exchange, int status, Object body) throws IOException {
        addCors(exchange);
        byte[] bytes = Json.stringify(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private void addCors(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws IOException;
    }

    private record JsonHandler(ExchangeHandler delegate) implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            delegate.handle(exchange);
        }
    }

    @FunctionalInterface
    public interface ContextSupplier {
        Context get();
    }

    public record Context(FilesMcpClient mcpClient, List<Map<String, Object>> mcpTools) {
    }
}
