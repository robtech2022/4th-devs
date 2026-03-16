package com.mcptranslatorjava;

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
    private static final String OPENAI_ENDPOINT = "https://api.openai.com/v1/responses";
    private static final String OPENROUTER_ENDPOINT = "https://openrouter.ai/api/v1/responses";
    private static final String DEFAULT_MODEL = "gpt-5.2";
    private static final String DEFAULT_INSTRUCTIONS = """
            You are a professional Polish-to-English translator with expertise in technical and educational content.

            PHILOSOPHY
            Great translation is invisible - natural, fluent, as if originally written in English. You translate meaning and voice, not just words.

            PROCESS
            1. SCAN - Check file metadata first using fs_read with mode list and details true. Never load the full file blindly.
            2. PLAN - If file is 100 lines or fewer: read and translate in one pass. If file is over 100 lines: work in chunks of about 80 lines.
            3. TRANSLATE - For each chunk: read it, translate it, write or append it, then move to the next chunk.
            4. VERIFY - Read the translated file. Compare line counts with source. Ensure nothing was skipped.

            CHUNKING RULES
            - First chunk: read lines 1-80, translate, write with operation create
            - Next chunks: read lines 81-160, etc., append using operation update with action insert_after
            - Continue until the file is complete

            CRAFT
            - Sound native, not translated
            - Preserve author's voice and tone
            - Adapt idioms naturally
            - Keep all formatting: headers, lists, code blocks, links, images

            Only say Done: <filename> after verification.
            """;

    private final String provider;
    private final String endpoint;
    private final String apiKey;
    private final Map<String, String> extraHeaders;
    private final String model;
    private final String instructions;
    private final int maxOutputTokens;
    private final String host;
    private final int port;
    private final String sourceDir;
    private final String targetDir;
    private final int pollIntervalMs;
    private final List<String> supportedExtensions;

    private Config(String provider,
                   String endpoint,
                   String apiKey,
                   Map<String, String> extraHeaders,
                   String model,
                   String instructions,
                   int maxOutputTokens,
                   String host,
                   int port,
                   String sourceDir,
                   String targetDir,
                   int pollIntervalMs,
                   List<String> supportedExtensions) {
        this.provider = provider;
        this.endpoint = endpoint;
        this.apiKey = apiKey;
        this.extraHeaders = Map.copyOf(extraHeaders);
        this.model = model;
        this.instructions = instructions;
        this.maxOutputTokens = maxOutputTokens;
        this.host = host;
        this.port = port;
        this.sourceDir = sourceDir;
        this.targetDir = targetDir;
        this.pollIntervalMs = pollIntervalMs;
        this.supportedExtensions = List.copyOf(supportedExtensions);
    }

    public String endpoint() {
        return endpoint;
    }

    public String apiKey() {
        return apiKey;
    }

    public Map<String, String> extraHeaders() {
        return extraHeaders;
    }

    public String resolveModelForProvider(String model) {
        if (!"openrouter".equals(provider) || model.contains("/")) {
            return model;
        }
        return model.startsWith("gpt-") ? "openai/" + model : model;
    }

    public String model() {
        return resolveModelForProvider(model);
    }

    public String instructions() {
        return instructions;
    }

    public int maxOutputTokens() {
        return maxOutputTokens;
    }

    public String host() {
        return host;
    }

    public int port() {
        return port;
    }

    public String sourceDir() {
        return sourceDir;
    }

    public String targetDir() {
        return targetDir;
    }

    public int pollIntervalMs() {
        return pollIntervalMs;
    }

    public List<String> supportedExtensions() {
        return supportedExtensions;
    }

    public static Config load() throws IOException {
        String openAiKey = env("OPENAI_API_KEY");
        String openRouterKey = env("OPENROUTER_API_KEY");
        String requestedProvider = env("AI_PROVIDER").toLowerCase(Locale.ROOT).trim();

        boolean hasOpenAi = !openAiKey.isBlank();
        boolean hasOpenRouter = !openRouterKey.isBlank();
        if (!hasOpenAi && !hasOpenRouter) {
            throw new RuntimeException("No API key set. Add OPENAI_API_KEY or OPENROUTER_API_KEY");
        }

        Set<String> validProviders = Set.of("", "openai", "openrouter");
        if (!validProviders.contains(requestedProvider)) {
            throw new RuntimeException("AI_PROVIDER must be one of: openai, openrouter");
        }

        String provider;
        if (!requestedProvider.isBlank()) {
            provider = requestedProvider;
            if ("openai".equals(provider) && !hasOpenAi) {
                throw new RuntimeException("AI_PROVIDER=openai requires OPENAI_API_KEY");
            }
            if ("openrouter".equals(provider) && !hasOpenRouter) {
                throw new RuntimeException("AI_PROVIDER=openrouter requires OPENROUTER_API_KEY");
            }
        } else {
            provider = hasOpenAi ? "openai" : "openrouter";
        }

        String endpoint = "openrouter".equals(provider) ? OPENROUTER_ENDPOINT : OPENAI_ENDPOINT;
        String apiKey = "openrouter".equals(provider) ? openRouterKey : openAiKey;

        Map<String, String> extraHeaders = new LinkedHashMap<>();
        if ("openrouter".equals(provider)) {
            String referer = env("OPENROUTER_HTTP_REFERER");
            String appName = env("OPENROUTER_APP_NAME");
            if (!referer.isBlank()) {
                extraHeaders.put("HTTP-Referer", referer);
            }
            if (!appName.isBlank()) {
                extraHeaders.put("X-Title", appName);
            }
        }

        String host = envOrDefault("HOST", "localhost");
        int port = parseInt(envOrDefault("PORT", "3000"), 3000);

        return new Config(
                provider,
                endpoint,
                apiKey,
                extraHeaders,
                DEFAULT_MODEL,
                DEFAULT_INSTRUCTIONS,
                16384,
                host,
                port,
                "translate",
                "translated",
                5000,
                List.of(".md", ".txt", ".html", ".json")
        );
    }

    private static int parseInt(String raw, int defaultValue) {
        try {
            return Integer.parseInt(raw);
        } catch (Exception ex) {
            return defaultValue;
        }
    }

    private static String envOrDefault(String key, String defaultValue) throws IOException {
        String value = env(key);
        return value.isBlank() ? defaultValue : value;
    }

    private static String env(String key) throws IOException {
        String envVal = System.getenv(key);
        if (envVal != null && !envVal.isBlank()) {
            return envVal.trim();
        }

        Path cwd = Path.of(".").toAbsolutePath().normalize();
        List<Path> candidates = List.of(
                cwd.resolve(".env"),
                cwd.resolve("4th-devs/.env"),
                cwd.resolve("../.env").normalize(),
                cwd.resolve("../../.env").normalize(),
                ProjectPaths.projectRoot().resolve(".env")
        );

        for (Path envPath : candidates) {
            if (!Files.exists(envPath)) {
                continue;
            }
            for (String line : Files.readAllLines(envPath, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.startsWith("#") || !trimmed.contains("=")) {
                    continue;
                }
                if (trimmed.startsWith(key + "=")) {
                    return trimmed.substring((key + "=").length()).trim();
                }
            }
        }

        return "";
    }
}
