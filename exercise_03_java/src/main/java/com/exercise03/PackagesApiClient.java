package com.exercise03;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Map;

public final class PackagesApiClient {
    private final Config config;

    public PackagesApiClient(Config config) {
        this.config = config;
    }

    public JsonNode checkPackage(String packageId) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("apikey", config.hubApiKey());
        body.put("action", "check");
        body.put("packageid", packageId);

        Map<String, String> headers = Map.of("Content-Type", "application/json");
        return ApiClient.fetchJson(config.packagesEndpoint(), body, headers);
    }

    public JsonNode redirectPackage(String packageId, String destination, String code) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("apikey", config.hubApiKey());
        body.put("action", "redirect");
        body.put("packageid", packageId);
        body.put("destination", destination);
        body.put("code", code);

        Map<String, String> headers = Map.of("Content-Type", "application/json");
        return ApiClient.fetchJson(config.packagesEndpoint(), body, headers);
    }
}
