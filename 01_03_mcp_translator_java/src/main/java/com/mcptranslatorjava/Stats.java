package com.mcptranslatorjava;

import com.fasterxml.jackson.databind.JsonNode;

public final class Stats {
    private int requests;
    private int inputTokens;
    private int cachedTokens;
    private int outputTokens;
    private int totalTokens;

    public synchronized void recordUsage(JsonNode usage) {
        if (usage == null || usage.isMissingNode() || usage.isNull()) {
            return;
        }

        requests++;
        inputTokens += usage.path("input_tokens").asInt(0);
        cachedTokens += usage.path("input_tokens_details").path("cached_tokens").asInt(0);
        outputTokens += usage.path("output_tokens").asInt(0);
        totalTokens += usage.path("total_tokens").asInt(0);
    }

    public synchronized String summary() {
        String cacheHitRate = inputTokens > 0
                ? String.format("%.1f%%", (cachedTokens * 100.0) / inputTokens)
                : "0%";
        return requests + " requests | " + totalTokens + " tokens | Cache: " + cacheHitRate + " (" + cachedTokens + "/" + inputTokens + ")";
    }
}
