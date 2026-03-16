package com.mcptranslatorjava;

public final class Log {
    private Log() {
    }

    public static void box(String text) {
        System.out.println();
        System.out.println("============================================");
        System.out.println(text);
        System.out.println("============================================");
    }

    public static void info(String text) {
        System.out.println("[info] " + text);
    }

    public static void success(String text) {
        System.out.println("[ok] " + text);
    }

    public static void warn(String text) {
        System.out.println("[warn] " + text);
    }

    public static void error(String text) {
        System.err.println("[error] " + text);
    }

    public static void error(String title, String detail) {
        System.err.println("[error] " + title + ": " + detail);
    }

    public static void debug(String text) {
        System.out.println("[debug] " + text);
    }

    public static void start(String text) {
        System.out.println("[start] " + text);
    }

    public static void ready(String text) {
        System.out.println("[ready] " + text);
    }

    public static void tool(String name, Object args) {
        String argsStr = String.valueOf(args);
        if (argsStr.length() > 200) {
            argsStr = argsStr.substring(0, 197) + "...";
        }
        info("tool " + name + " " + argsStr);
    }

    public static void toolResult(String name, boolean success, String detail) {
        String text = detail == null ? "" : detail;
        if (text.length() > 200) {
            text = text.substring(0, 197) + "...";
        }
        if (success) {
            Log.success(name + " -> " + (text.isBlank() ? "OK" : text));
        } else {
            Log.warn(name + " -> " + (text.isBlank() ? "Failed" : text));
        }
    }

    public static void api(String action, int historyLength) {
        info("api " + action + " (" + historyLength + " messages)");
    }

    public static void apiDone(JsonNodeUsage usage) {
        if (usage == null) {
            success("api done");
            return;
        }
        success("api in:" + usage.inputTokens + " out:" + usage.outputTokens + " cached:" + usage.cachedTokens);
    }

    public static void query(String text) {
        String truncated = text.length() > 60 ? text.substring(0, 57) + "..." : text;
        info("query " + truncated);
    }

    public static void response(String text) {
        String truncated = text.length() > 100 ? text.substring(0, 97) + "..." : text;
        success("response " + truncated);
    }

    public static void endpoint(String method, String path, String description) {
        info(method + " " + path + " " + description);
    }

    public record JsonNodeUsage(int inputTokens, int outputTokens, int cachedTokens) {
    }
}
