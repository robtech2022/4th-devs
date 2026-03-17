package com.mcpuploadjava;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class Config {

    private static final String OPENAI_ENDPOINT    = "https://api.openai.com/v1/responses";
    private static final String OPENROUTER_ENDPOINT = "https://openrouter.ai/api/v1/responses";
    private static final String DEFAULT_MODEL = "gpt-5.4";

    private static final String DEFAULT_INSTRUCTIONS = """
            You are a file upload assistant.

            Use the {{file:path}} placeholder for the base64 field when uploading — the system resolves it automatically.

            Example: { "files": [{ "base64": "{{file:example.md}}", "name": "example.md", "type": "text/markdown" }] }

            Workflow:
            1. fs_read with mode:"list" to see workspace files
            2. Upload each file not already in uploaded.md using {{file:path}} syntax
            3. Update uploaded.md with a table of filename, URL, and timestamp

            Rules:
            - Never read or encode file content yourself — always use {{file:path}}
            - Skip uploaded.md itself and files already listed in it
            - Handle errors gracefully

            When done, say "Upload complete: X files uploaded, Y skipped."
            """;

    private final String provider;
    private final String endpoint;
    private final String apiKey;
    private final Map<String, String> extraHeaders;
    private final String model;
    private final String instructions;
    private final int maxOutputTokens;

    private Config(String provider, String endpoint, String apiKey,
                   Map<String, String> extraHeaders, String model,
                   String instructions, int maxOutputTokens) {
        this.provider       = provider;
        this.endpoint       = endpoint;
        this.apiKey         = apiKey;
        this.extraHeaders   = Map.copyOf(extraHeaders);
        this.model          = model;
        this.instructions   = instructions;
        this.maxOutputTokens = maxOutputTokens;
    }

    public String endpoint()          { return endpoint; }
    public String apiKey()            { return apiKey; }
    public Map<String, String> extraHeaders() { return extraHeaders; }
    public String instructions()      { return instructions; }
    public int maxOutputTokens()      { return maxOutputTokens; }

    /** Returns the model, prefixed for OpenRouter when needed. */
    public String model() {
        if ("openrouter".equals(provider) && !model.contains("/")) {
            return model.startsWith("gpt-") ? "openai/" + model : model;
        }
        return model;
    }

    public static Config load() throws IOException {
        String openAiKey      = env("OPENAI_API_KEY");
        String openRouterKey  = env("OPENROUTER_API_KEY");
        String reqProvider    = env("AI_PROVIDER").toLowerCase(Locale.ROOT).trim();

        boolean hasOpenAi     = !openAiKey.isBlank();
        boolean hasOpenRouter = !openRouterKey.isBlank();

        if (!hasOpenAi && !hasOpenRouter) {
            throw new RuntimeException("No API key set. Add OPENAI_API_KEY or OPENROUTER_API_KEY to .env");
        }
        if (!Set.of("", "openai", "openrouter").contains(reqProvider)) {
            throw new RuntimeException("AI_PROVIDER must be one of: openai, openrouter");
        }

        String provider;
        if (!reqProvider.isBlank()) {
            provider = reqProvider;
            if ("openai".equals(provider) && !hasOpenAi)
                throw new RuntimeException("AI_PROVIDER=openai requires OPENAI_API_KEY");
            if ("openrouter".equals(provider) && !hasOpenRouter)
                throw new RuntimeException("AI_PROVIDER=openrouter requires OPENROUTER_API_KEY");
        } else {
            provider = hasOpenAi ? "openai" : "openrouter";
        }

        String endpoint = "openrouter".equals(provider) ? OPENROUTER_ENDPOINT : OPENAI_ENDPOINT;
        String apiKey   = "openrouter".equals(provider) ? openRouterKey       : openAiKey;

        Map<String, String> extraHeaders = new LinkedHashMap<>();
        if ("openrouter".equals(provider)) {
            String referer  = env("OPENROUTER_HTTP_REFERER");
            String appName  = env("OPENROUTER_APP_NAME");
            if (!referer.isBlank())  extraHeaders.put("HTTP-Referer", referer);
            if (!appName.isBlank())  extraHeaders.put("X-Title",      appName);
        }

        return new Config(provider, endpoint, apiKey, extraHeaders,
                DEFAULT_MODEL, DEFAULT_INSTRUCTIONS, 16384);
    }

    // -----------------------------------------------------------------------
    // reads a key from the environment or from the nearest .env file
    // -----------------------------------------------------------------------
    private static String env(String key) throws IOException {
        String val = System.getenv(key);
        if (val != null && !val.isBlank()) return val.trim();

        Path cwd = Path.of(".").toAbsolutePath().normalize();
        List<Path> candidates = List.of(
                cwd.resolve(".env"),
                cwd.resolve("4th-devs/.env"),
                cwd.resolve("../.env").normalize(),
                cwd.resolve("../../.env").normalize(),
                ProjectPaths.projectRoot().resolve(".env")
        );

        for (Path envPath : candidates) {
            if (!Files.exists(envPath)) continue;
            for (String line : Files.readAllLines(envPath, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.startsWith("#") || !trimmed.contains("=")) continue;
                if (trimmed.startsWith(key + "=")) {
                    return trimmed.substring(key.length() + 1).trim();
                }
            }
        }
        return "";
    }
}
