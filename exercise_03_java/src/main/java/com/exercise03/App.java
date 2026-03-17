package com.exercise03;

public class App {
    private static final String DEFAULT_MODEL = "gpt-4o-mini";
    private static final int DEFAULT_PORT = 8080;

    public static void main(String[] args) throws Exception {
        System.out.println("🔧 Loading configuration...");
        Config config = Config.load();
        
        System.out.println("✅ Configuration loaded");
        System.out.println("   Provider: " + config.provider());
        System.out.println("   Model: " + DEFAULT_MODEL);
        
        // Initialize components
        ApiClient apiClient = new ApiClient(config);
        PackagesApiClient packagesApi = new PackagesApiClient(config);
        ToolHandlers toolHandlers = new ToolHandlers(packagesApi);
        Executor executor = new Executor(apiClient, toolHandlers);
        SessionManager sessionManager = new SessionManager(true); // Enable disk persistence
        
        // Determine model
        String model = config.resolveModelForProvider(DEFAULT_MODEL);
        
        // Determine port from environment or use default
        int port = getPort();
        
        // Start server
        ProxyServer server = new ProxyServer(sessionManager, executor, model, port);
        server.start();
        
        // Keep application running
        System.out.println("\nPress Ctrl+C to stop the server.");
        Thread.currentThread().join();
    }

    private static int getPort() {
        String portEnv = System.getenv("PORT");
        if (portEnv != null && !portEnv.isBlank()) {
            try {
                return Integer.parseInt(portEnv.trim());
            } catch (NumberFormatException e) {
                System.err.println("Invalid PORT value: " + portEnv + ", using default " + DEFAULT_PORT);
            }
        }
        return DEFAULT_PORT;
    }
}
