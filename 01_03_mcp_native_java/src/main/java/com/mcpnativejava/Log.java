package com.mcpnativejava;

public final class Log {
    private Log() {
    }

    public static final String MCP_LABEL = "MCP";
    public static final String NATIVE_LABEL = "Native";

    public static void query(String text) {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("Query: " + text);
        System.out.println("=".repeat(60));
    }

    public static void toolCall(String label, String name, Object args) {
        System.out.println("  [" + label + "] " + name + "(" + args + ")");
    }

    public static void toolResult(Object result) {
        System.out.println("       + " + result);
    }

    public static void toolError(String error) {
        System.out.println("       x Error: " + error);
    }

    public static void toolCount(int count) {
        System.out.println("\nTool calls: " + count);
    }

    public static void response(String text) {
        System.out.println("\nAssistant: " + text);
    }
}