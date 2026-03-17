package com.exercise05;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class App {
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String API_URL = "https://hub.ag3nts.org/verify";
    private static final String TASK_NAME = "railway";
    private static final int MAX_RETRIES = 5;
    private static final int INITIAL_BACKOFF_MS = 1000;
    
    private static String AG3NTS_API_KEY;
    private static long rateLimitResetTime = 0;
    
    static {
        AG3NTS_API_KEY = loadEnv("AG3NTS_API_KEY");
    }
    
    private static String loadEnv(String key) {
        String envVal = System.getenv(key);
        if (envVal != null && !envVal.isBlank()) {
            return envVal.trim();
        }

        Path cwd = Paths.get(".").toAbsolutePath().normalize();
        List<Path> candidates = List.of(
                cwd.resolve(".env"),
                cwd.resolve("../.env").normalize(),
                cwd.resolve("../../.env").normalize()
        );

        for (Path envPath : candidates) {
            if (!Files.exists(envPath)) {
                continue;
            }

            try {
                for (String line : Files.readAllLines(envPath, StandardCharsets.UTF_8)) {
                    String trimmed = line.trim();
                    if (trimmed.startsWith("#") || !trimmed.contains("=")) {
                        continue;
                    }
                    if (trimmed.startsWith(key + "=")) {
                        return trimmed.substring((key + "=").length()).trim();
                    }
                }
            } catch (IOException e) {
                // Continue to next candidate
            }
        }

        return null;
    }
    
    public static void main(String[] args) throws Exception {
        System.out.println("🚂 Railway Route Activation Task");
        System.out.println("================================\n");
        
        if (AG3NTS_API_KEY == null || AG3NTS_API_KEY.isEmpty()) {
            System.err.println("❌ Error: AG3NTS_API_KEY not found in environment");
            return;
        }
        
        System.out.println("📖 Step 1: Getting API documentation via 'help' action...\n");
        Map<String, Object> helpResponse = callApi("help", null);
        
        if (helpResponse == null) {
            System.err.println("❌ Failed to get help documentation");
            return;
        }
        
        System.out.println("✅ API Documentation received:");
        System.out.println(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(helpResponse));
        System.out.println("\n" + "=".repeat(60) + "\n");
        
        // Parse the help response to understand the API structure
        String helpMessage = (String) helpResponse.get("message");
        if (helpMessage != null && helpMessage.contains("{FLG:")) {
            System.out.println("🎉 FLAG FOUND IN HELP: " + extractFlag(helpMessage));
            return;
        }
        
        // Follow the documented API flow
        // The help response should tell us what actions are available
        System.out.println("📋 Analyzed API documentation");
        System.out.println("Workflow to activate route X-01:");
        System.out.println("  1. getstatus - Check current status");
        System.out.println("  2. reconfigure - Enable reconfigure mode");
        System.out.println("  3. setstatus with value=RTOPEN - Open the route");
        System.out.println("  4. save - Save changes\n");
        
        activateRoute();
    }
    
    private static void activateRoute() throws Exception {
        String route = "X-01";
        
        // Step 1: Check current status
        System.out.println("🔍 Step 1: Checking current status of route " + route + "...");
        Map<String, Object> statusResponse = callApi("getstatus", Map.of("route", route));
        
        if (statusResponse != null) {
            System.out.println("Current status: " + MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(statusResponse) + "\n");
            
            String message = statusResponse.get("message") != null ? statusResponse.get("message").toString() : "";
            if (message.contains("{FLG:")) {
                System.out.println("🎉 FLAG FOUND: " + extractFlag(message));
                return;
            }
        } else {
            System.err.println("❌ Failed to get status");
            return;
        }
        
        // Step 2: Enable reconfigure mode
        System.out.println("🔧 Step 2: Enabling reconfigure mode for route " + route + "...");
        Map<String, Object> reconfigResponse = callApi("reconfigure", Map.of("route", route));
        
        if (reconfigResponse != null) {
            System.out.println("Reconfigure response: " + MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(reconfigResponse) + "\n");
            
            String message = reconfigResponse.get("message") != null ? reconfigResponse.get("message").toString() : "";
            if (message.contains("{FLG:")) {
                System.out.println("🎉 FLAG FOUND: " + extractFlag(message));
                return;
            }
        } else {
            System.err.println("❌ Failed to enable reconfigure mode");
            return;
        }
        
        // Step 3: Set status to RTOPEN (open/activate the route)
        System.out.println("✅ Step 3: Setting route status to RTOPEN (open)...");
        Map<String, Object> setStatusResponse = callApi("setstatus", Map.of("route", route, "value", "RTOPEN"));
        
        if (setStatusResponse != null) {
            System.out.println("Set status response: " + MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(setStatusResponse) + "\n");
            
            String message = setStatusResponse.get("message") != null ? setStatusResponse.get("message").toString() : "";
            if (message.contains("{FLG:")) {
                System.out.println("🎉 FLAG FOUND: " + extractFlag(message));
                return;
            }
        } else {
            System.err.println("❌ Failed to set status");
            return;
        }
        
        // Step 4: Save changes
        System.out.println("💾 Step 4: Saving changes...");
        Map<String, Object> saveResponse = callApi("save", Map.of("route", route));
        
        if (saveResponse != null) {
            System.out.println("Save response: " + MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(saveResponse) + "\n");
            
            String message = saveResponse.get("message") != null ? saveResponse.get("message").toString() : "";
            if (message.contains("{FLG:")) {
                System.out.println("🎉 FLAG FOUND: " + extractFlag(message));
                return;
            }
        } else {
            System.err.println("❌ Failed to save");
            return;
        }
        
        System.out.println("✅ Route activation sequence completed!");
    }
    
    private static String extractFlag(String message) {
        Pattern pattern = Pattern.compile("\\{FLG:[^}]+\\}");
        Matcher matcher = pattern.matcher(message);
        if (matcher.find()) {
            return matcher.group();
        }
        return null;
    }
    
    private static Map<String, Object> callApi(String action, Map<String, Object> additionalParams) {
        int retries = 0;
        int backoffMs = INITIAL_BACKOFF_MS;
        
        while (retries < MAX_RETRIES) {
            try {
                // Check if we need to wait for rate limit reset
                long now = System.currentTimeMillis();
                if (rateLimitResetTime > now) {
                    long waitTime = rateLimitResetTime - now + 100; // Add 100ms buffer
                    System.out.println("⏳ Rate limit active. Waiting " + waitTime + "ms until reset...");
                    Thread.sleep(waitTime);
                }
                
                // Build request
                Map<String, Object> requestBody = new HashMap<>();
                requestBody.put("apikey", AG3NTS_API_KEY);
                requestBody.put("task", TASK_NAME);
                
                Map<String, Object> answer = new HashMap<>();
                answer.put("action", action);
                if (additionalParams != null) {
                    answer.putAll(additionalParams);
                }
                requestBody.put("answer", answer);
                
                String jsonBody = MAPPER.writeValueAsString(requestBody);
                
                System.out.println("📤 Request: " + jsonBody);
                
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(API_URL))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();
                
                HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
                
                // Log response headers (for rate limit info)
                System.out.println("📥 Status: " + response.statusCode());
                response.headers().map().forEach((key, values) -> {
                    if (key.toLowerCase().contains("limit") || key.toLowerCase().contains("retry") || 
                        key.toLowerCase().contains("reset") || key.toLowerCase().contains("remaining")) {
                        System.out.println("   " + key + ": " + String.join(", ", values));
                        
                        // Parse rate limit reset time
                        if (key.toLowerCase().contains("reset")) {
                            try {
                                long resetTime = Long.parseLong(values.get(0));
                                if (resetTime > 1000000000000L) {
                                    // Timestamp in milliseconds
                                    rateLimitResetTime = resetTime;
                                } else {
                                    // Timestamp in seconds
                                    rateLimitResetTime = resetTime * 1000;
                                }
                            } catch (NumberFormatException e) {
                                // Ignore
                            }
                        }
                    }
                });
                
                // Handle 503 - Service Unavailable (simulated overload)
                if (response.statusCode() == 503) {
                    System.out.println("⚠️  503 Service Unavailable (simulated overload)");
                    System.out.println("   Retry " + (retries + 1) + "/" + MAX_RETRIES + " after " + backoffMs + "ms\n");
                    Thread.sleep(backoffMs);
                    backoffMs *= 2; // Exponential backoff
                    retries++;
                    continue;
                }
                
                // Handle rate limit (429)
                if (response.statusCode() == 429) {
                    System.out.println("⚠️  429 Too Many Requests");
                    String retryAfter = response.headers().firstValue("Retry-After").orElse("60");
                    long waitSeconds = Long.parseLong(retryAfter);
                    System.out.println("   Waiting " + waitSeconds + " seconds...\n");
                    Thread.sleep(waitSeconds * 1000);
                    retries++;
                    continue;
                }
                
                System.out.println("📥 Response: " + response.body() + "\n");
                
                // Parse response
                if (response.statusCode() == 200) {
                    return MAPPER.readValue(response.body(), Map.class);
                } else {
                    System.err.println("❌ Error: HTTP " + response.statusCode());
                    System.err.println("   " + response.body());
                    return null;
                }
                
            } catch (InterruptedException e) {
                System.err.println("❌ Interrupted: " + e.getMessage());
                Thread.currentThread().interrupt();
                return null;
            } catch (Exception e) {
                System.err.println("❌ Exception: " + e.getMessage());
                e.printStackTrace();
                retries++;
                if (retries < MAX_RETRIES) {
                    try {
                        System.out.println("   Retry " + retries + "/" + MAX_RETRIES + " after " + backoffMs + "ms\n");
                        Thread.sleep(backoffMs);
                        backoffMs *= 2;
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                }
            }
        }
        
        System.err.println("❌ Max retries exceeded");
        return null;
    }
}
