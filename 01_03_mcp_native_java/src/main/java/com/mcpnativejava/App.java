package com.mcpnativejava;

import com.mcpnativejava.mcp.McpClient;
import com.mcpnativejava.mcp.McpServer;
import com.mcpnativejava.nativeimpl.NativeTools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class App {
    private static final String MODEL = "gpt-5.2";
    private static final String INSTRUCTIONS = """
            You are a helpful assistant with access to various tools.
            You can check weather, get time, perform calculations, and transform text.
            Use the appropriate tool for each task. Be concise.
            """;

    public static void main(String[] args) throws Exception {
        Config config = Config.load();
        String model = config.resolveModelForProvider(MODEL);

        McpServer mcpServer = new McpServer();
        McpClient mcpClient = new McpClient(mcpServer);
        List<McpServer.McpTool> mcpTools = mcpClient.listTools();

        Map<String, Agent.ToolHandler> handlers = new LinkedHashMap<>();
        for (McpServer.McpTool tool : mcpTools) {
            handlers.put(tool.name(), new Agent.ToolHandler(
                    Log.MCP_LABEL,
                    argsNode -> mcpClient.callTool(tool.name(), argsNode)
            ));
        }

        handlers.put("calculate", new Agent.ToolHandler(Log.NATIVE_LABEL, NativeTools::calculate));
        handlers.put("uppercase", new Agent.ToolHandler(Log.NATIVE_LABEL, NativeTools::uppercase));

        List<Map<String, Object>> tools = new ArrayList<>();
        tools.addAll(McpClient.toOpenAiTools(mcpTools));
        tools.addAll(NativeTools.definitions());

        ApiClient api = new ApiClient(config);
        Agent agent = new Agent(api, model, tools, INSTRUCTIONS, handlers);

        System.out.println("MCP tools: " + mcpTools.stream().map(McpServer.McpTool::name).toList());
        System.out.println("Native tools: " + List.of("calculate", "uppercase"));

        List<String> queries = List.of(
                "What's the weather in Tokyo?",
                "What time is it in Europe/London?",
                "Calculate 42 multiplied by 17",
                "Convert 'hello world' to uppercase",
                "What's 25 + 17, and what's the weather in Paris?"
        );

        for (String query : queries) {
            agent.processQuery(query);
        }
    }
}