package com.mcpuploadjava;

import com.mcpuploadjava.mcp.McpClientManager;

import java.util.List;
import java.util.Map;

/**
 * Entry point for the MCP Upload Agent.
 *
 * <p>Connects to all MCP servers defined in {@code mcp.json}, then runs the agent loop
 * which lists workspace files, uploads new ones, and updates {@code uploaded.md}.
 */
public final class App {
    private App() {}

    public static void main(String[] args) throws Exception {
        Log.box("MCP Upload Agent\nUpload workspace files via uploadthing");

        Config config   = Config.load();
        Stats  stats    = new Stats();
        ResponsesApiClient apiClient = new ResponsesApiClient(config, stats);

        McpClientManager mcpManager = null;
        try {
            Log.start("Connecting to MCP servers...");
            mcpManager = McpClientManager.connect();

            List<Map<String, Object>> mcpTools = mcpManager.listAllTools();
            Log.success("Connected with " + mcpTools.size() + " tools from "
                    + mcpManager.serverCount() + " servers");

            Agent agent = new Agent(apiClient, config);

            Log.start("Starting upload task...");
            Agent.Result result = agent.run(
                    "Check the workspace for files, upload any that haven't been uploaded yet,"
                    + " and update uploaded.md with the results.",
                    mcpManager, mcpTools);

            Log.response(result.response());
            Log.info("Stats: " + stats.summary());

        } catch (ConfigurationException ex) {
            Log.error(ex.getMessage());
            System.exit(1);
        } catch (Exception ex) {
            Log.error("Fatal error: " + (ex.getMessage() != null ? ex.getMessage() : ex.getClass().getName()));
            ex.printStackTrace(System.err);
            System.exit(1);
        } finally {
            if (mcpManager != null) {
                Log.start("Closing connections...");
                mcpManager.closeAll();
            }
        }
    }
}
