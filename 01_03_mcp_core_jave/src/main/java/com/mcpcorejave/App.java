package com.mcpcorejave;

import java.util.List;
import java.util.Map;

public final class App {
    private static final String MODEL = "gpt-5.1";

    private App() {
    }

    public static void main(String[] args) throws Exception {
        Config config = Config.load();
        String model = config.resolveModelForProvider(MODEL);

        ResponsesApiClient ai = new ResponsesApiClient(config);
        McpServer server = new McpServer();
        McpClient client = new McpClient(
                server,
                McpClient.createSamplingHandler(ai, model),
                McpClient.createElicitationHandler()
        );

        Log.heading("TOOLS", "Actions the server exposes for the LLM to invoke");

        List<McpServer.ToolMeta> tools = client.listTools();
        Log.log("listTools", tools.stream().map(t -> t.name() + " - " + t.description()).toList());

        McpServer.ToolResult calcResult = client.callTool(
                "calculate",
                Map.of("operation", "multiply", "a", 42, "b", 17)
        );
        Log.log("callTool(calculate)", Log.parseToolResult(calcResult));

        McpServer.ToolResult summaryResult = client.callTool(
                "summarize_with_confirmation",
                Map.of(
                        "text", "The Model Context Protocol (MCP) is a standardized protocol that allows applications to provide context for LLMs. It separates the concerns of providing context from the actual LLM interaction. MCP servers expose tools, resources, and prompts that clients can discover and use.",
                        "maxLength", 30
                )
        );
        Log.log("callTool(summarize_with_confirmation)", Log.parseToolResult(summaryResult));

        Log.heading("RESOURCES", "Read-only data the server makes available to clients");

        List<McpServer.ResourceMeta> resources = client.listResources();
        Log.log("listResources", resources.stream().map(r -> r.uri() + " - " + r.name()).toList());

        McpClient.ResourceReadResult configResource = client.readResource("config://project");
        Log.log("readResource(config://project)", Json.parse(configResource.contents().getFirst().text()));

        McpClient.ResourceReadResult statsResource = client.readResource("data://stats");
        Log.log("readResource(data://stats)", Json.parse(statsResource.contents().getFirst().text()));

        Log.heading("PROMPTS", "Reusable message templates with parameters");

        List<McpServer.PromptMeta> prompts = client.listPrompts();
        Log.log("listPrompts", prompts.stream().map(p -> p.name() + " - " + p.description()).toList());

        McpServer.PromptResult promptResult = client.getPrompt(
                "code-review",
                Map.of(
                        "code", "function add(a, b) { return a + b; }",
                        "language", "javascript",
                        "focus", "readability"
                )
        );

        List<String> renderedMessages = promptResult.messages().stream()
                .map(m -> "[" + m.role() + "] " + m.text())
                .toList();
        Log.log("getPrompt(code-review)", renderedMessages);
    }
}
