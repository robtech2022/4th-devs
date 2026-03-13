package com.toolusejava;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public class App {
    private static final String MODEL = "gpt-4.1";
    private static final String INSTRUCTIONS = """
            You are a helpful assistant with access to a sandboxed filesystem.
            You can list, read, write, and delete files within the sandbox.
            Always use the available tools to interact with files.
            Be concise in your responses.
            """;

    private static final List<String> QUERIES = List.of(
            "What files are in the sandbox? Create a new file test1.txt with content 'This is a test file.' and then list files again."
            //"Create a file called hello.txt with content: 'Hello, World!'",
            // "Read the hello.txt file",
            // "Get info about hello.txt",
            // "Create a directory called 'docs'",
            // "Create a file docs/readme.txt with content: 'Documentation folder'",
            // "List files in the docs directory",
            // "Delete the hello.txt file",
            // "Try to read ../config.js"
    );

    public static void main(String[] args) throws Exception {
        Config config = Config.load();
        String model = config.resolveModelForProvider(MODEL);

        Path sandboxPath = Path.of("01_02_tool_use_java", "sandbox");
        if (!sandboxPath.toFile().exists()) {
            sandboxPath = Path.of("sandbox");
        }

        SandboxUtils sandbox = new SandboxUtils(sandboxPath);
        sandbox.initializeSandbox();
        System.out.println("Sandbox prepared: empty state\n");

        ApiClient api = new ApiClient(config);
        ToolHandlers handlers = new ToolHandlers(sandbox);
        Executor executor = new Executor(api, handlers);
        List<Map<String, Object>> tools = ToolDefinitions.tools();

        for (String query : QUERIES) {
            executor.processQuery(query, model, tools, INSTRUCTIONS);
        }
    }
}
