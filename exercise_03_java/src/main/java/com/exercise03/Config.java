package com.exercise03;

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
    private static final String OPENAI_ENDPOINT = "https://api.openai.com/v1/chat/completions";
    private static final String OPENROUTER_ENDPOINT = "https://openrouter.ai/api/v1/chat/completions";
    private static final String HUB_PACKAGES_ENDPOINT = "https://hub.ag3nts.org/api/packages";

    private final String provider;
    private final String endpoint;
    private final String apiKey;
    private final String hubApiKey;
    private final Map<String, String> extraHeaders;

    private Config(String provider, String endpoint, String apiKey, String hubApiKey, Map<String, String> extraHeaders) {
        this.provider = provider;
        this.endpoint = endpoint;
        this.apiKey = apiKey;
        this.hubApiKey = hubApiKey;
        this.extraHeaders = Map.copyOf(extraHeaders);
    }

    public String provider() {
        return provider;
    }

    public String endpoint() {
        return endpoint;
    }

    public String apiKey() {
        return apiKey;
    }

    public String hubApiKey() {
        return hubApiKey;
    }

    public String packagesEndpoint() {
        return HUB_PACKAGES_ENDPOINT;
    }

    public Map<String, String> extraHeaders() {
        return extraHeaders;
    }

    public String resolveModelForProvider(String model) {
        if (model == null || model.isBlank()) {
            throw new RuntimeException("Model must be a non-empty string");
        }

        if (!"openrouter".equals(provider) || model.contains("/")) {
            return model;
        }

        return model.startsWith("gpt-") ? "openai/" + model : model;
    }

    public static Config load() throws IOException {
        String openAiKey = env("OPENAI_API_KEY");
        String openRouterKey = env("OPENROUTER_API_KEY");
        String hubApiKey = env("AG3NTS_API_KEY");
        String requestedProvider = env("AI_PROVIDER").toLowerCase(Locale.ROOT).trim();

        if (hubApiKey.isBlank()) {
            throw new RuntimeException("AG3NTS_API_KEY is required");
        }

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

        return new Config(provider, endpoint, apiKey, hubApiKey, extraHeaders);
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
                cwd.resolve("../../.env").normalize()
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
