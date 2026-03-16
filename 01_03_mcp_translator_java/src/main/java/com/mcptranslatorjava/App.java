package com.mcptranslatorjava;

import com.sun.net.httpserver.HttpServer;
import com.mcptranslatorjava.files.FilesMcpClient;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

public final class App {
    private App() {
    }

    public static void main(String[] args) throws Exception {
        Log.box("MCP Translator Agent\nAccurate translations to English with tone, formatting, and nuances");

        Config config = Config.load();
        Stats stats = new Stats();
        ResponsesApiClient apiClient = new ResponsesApiClient(config, stats);
        Agent agent = new Agent(apiClient, config);

        Log.start("Connecting to MCP server...");
        FilesMcpClient mcpClient = FilesMcpClient.connect();
        List<Map<String, Object>> mcpTools = mcpClient.listTools();
        Log.success("Connected with " + mcpTools.size() + " tools: " + mcpTools.stream().map(t -> String.valueOf(t.get("name"))).toList());

        TranslationLoop loop = new TranslationLoop(config, agent, stats);
        loop.start(mcpClient, mcpTools);

        HttpApiServer apiServer = new HttpApiServer(config, agent);
        HttpServer server = apiServer.start(() -> new HttpApiServer.Context(mcpClient, mcpTools));

        CountDownLatch latch = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            Log.warn("Shutting down...");
            loop.stop();
            apiServer.stop();
            mcpClient.close();
            if (server != null) {
                server.stop(0);
            }
            latch.countDown();
        }));

        latch.await();
    }
}
