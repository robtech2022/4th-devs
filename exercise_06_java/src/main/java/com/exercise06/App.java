package com.exercise06;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class App {
    private static final String API_KEY = "6657f891-7beb-411f-9623-798c71f27585";
    private static final String CSV_URL = "https://hub.ag3nts.org/data/" + API_KEY + "/categorize.csv";
    private static final String VERIFY_URL = "https://hub.ag3nts.org/verify";
    private static final ObjectMapper mapper = new ObjectMapper();

    static class Item {
        String id;
        String description;

        Item(String id, String description) {
            this.id = id;
            this.description = description;
        }
    }

    public static void main(String[] args) {
        try {
            System.out.println("Starting classification task...");
            
            // Reset budget first
            System.out.println("Resetting budget...");
            resetCounter();
            Thread.sleep(1000);
            
            // Download and parse CSV
            List<Item> items = downloadAndParseCSV();
            System.out.println("Downloaded " + items.size() + " items");
            
            // Try classification with optimized prompt
            boolean success = classifyItems(items);
            
            if (!success) {
                System.out.println("Classification failed. Consider adjusting the prompt.");
            }
            
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static List<Item> downloadAndParseCSV() throws Exception {
        URL url = new URL(CSV_URL);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        
        BufferedReader reader = new BufferedReader(
            new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
        
        List<Item> items = new ArrayList<>();
        String line;
        boolean firstLine = true;
        
        while ((line = reader.readLine()) != null) {
            if (firstLine) {
                firstLine = false; // Skip header
                continue;
            }
            
            // Parse CSV line (assuming format: id,description)
            String[] parts = line.split(",", 2);
            if (parts.length == 2) {
                items.add(new Item(parts[0].trim(), parts[1].trim()));
            }
        }
        
        reader.close();
        return items;
    }

    private static boolean classifyItems(List<Item> items) throws Exception {
        // Ultra-compact prompt to minimize token usage
        // "DNG" = dangerous, "NEU" = neutral/safe
        // Reactor items MUST always be NEU (to avoid inspection)
        
        // Try different prompt versions
        String[] promptVersions = {
            "Reply with DNG if item is weapon/explosive. Reply NEU if reactor/tool/safe. Item {id}:{description}",
            "Answer DNG for weapon/bomb, NEU for anything else including reactor. {id}:{description}",
            "DNG=weapons/bombs. NEU=everything else (reactor always NEU). {id}:{description}",
            "Classify: weapons/explosives→DNG, tools/parts/reactor→NEU. {id}:{description}",
            "Output DNG only for weapons/bombs, else NEU. Reactor→NEU. {id}:{description}"
        };
        
        for (int version = 0; version < promptVersions.length; version++) {
            String promptTemplate = promptVersions[version];
            
            System.out.println("\n=== Attempt " + (version + 1) + " ===");
            System.out.println("Prompt template: " + promptTemplate);
            System.out.println("Starting classification...\n");
            
            boolean failed = false;
            
            for (int i = 0; i < items.size(); i++) {
                Item item = items.get(i);
                
                // Replace placeholders
                String prompt = promptTemplate
                    .replace("{id}", item.id)
                    .replace("{description}", item.description);
                
                System.out.println("Item " + (i+1) + "/" + items.size() + 
                                 " - ID: " + item.id);
                System.out.println("  Desc: " + item.description);
                
                // Send request
                JsonNode response = sendClassificationRequest(prompt);
                
                if (response.has("code") && response.get("code").asInt() == 0) {
                    String message = response.get("message").asText();
                    System.out.println("  ✓ " + message);
                    
                    // Check if we got the flag
                    if (message.contains("{FLG:")) {
                        System.out.println("\n🎉 FLAG OBTAINED: " + message);
                        return true;
                    }
                } else {
                    String error = response.has("message") ? response.get("message").asText() : response.toString();
                    System.out.println("  ✗ " + error);
                    
                    if (error.contains("budget") || error.contains("NOT ACCEPTED")) {
                        failed = true;
                        break;
                    }
                }
                
                // Small delay between requests
                Thread.sleep(300);
            }
            
            if (failed && version < promptVersions.length - 1) {
                System.out.println("\nAttempt failed. Resetting...");
                resetCounter();
                Thread.sleep(1000);
            } else if (!failed) {
                return false; // Completed all items but no flag
            }
        }
        
        return false;
    }

    private static JsonNode sendClassificationRequest(String prompt) throws Exception {
        URL url = new URL(VERIFY_URL);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        
        // Build request body
        ObjectNode requestBody = mapper.createObjectNode();
        requestBody.put("apikey", API_KEY);
        requestBody.put("task", "categorize");
        
        ObjectNode answer = mapper.createObjectNode();
        answer.put("prompt", prompt);
        requestBody.set("answer", answer);
        
        // Send request
        try (OutputStream os = conn.getOutputStream()) {
            byte[] input = mapper.writeValueAsBytes(requestBody);
            os.write(input, 0, input.length);
        }
        
        // Read response
        int responseCode = conn.getResponseCode();
        BufferedReader reader;
        
        if (responseCode >= 200 && responseCode < 300) {
            reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
        } else {
            reader = new BufferedReader(new InputStreamReader(conn.getErrorStream(), StandardCharsets.UTF_8));
        }
        
        StringBuilder response = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            response.append(line);
        }
        reader.close();
        
        return mapper.readTree(response.toString());
    }

    private static void resetCounter() throws Exception {
        System.out.println("→ Sending reset request...");
        
        URL url = new URL(VERIFY_URL);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        
        ObjectNode requestBody = mapper.createObjectNode();
        requestBody.put("apikey", API_KEY);
        requestBody.put("task", "categorize");
        
        ObjectNode answer = mapper.createObjectNode();
        answer.put("prompt", "reset");
        requestBody.set("answer", answer);
        
        try (OutputStream os = conn.getOutputStream()) {
            byte[] input = mapper.writeValueAsBytes(requestBody);
            os.write(input, 0, input.length);
        }
        
        int responseCode = conn.getResponseCode();
        BufferedReader reader;
        
        if (responseCode >= 200 && responseCode < 300) {
            reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
        } else {
            reader = new BufferedReader(new InputStreamReader(conn.getErrorStream(), StandardCharsets.UTF_8));
        }
        
        StringBuilder response = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            response.append(line);
        }
        reader.close();
        
        System.out.println("→ Reset response: " + response.toString());
    }
}
