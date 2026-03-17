package com.exercise04;

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
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class App {
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DOC_BASE_URL = "https://hub.ag3nts.org/dane/doc/";
    private static final Path DOCS_DIR = Paths.get("docs");
    private static String OPENAI_API_KEY;
    private static String AG3NTS_API_KEY;
    
    static {
        // Load environment variables from .env files
        OPENAI_API_KEY = loadEnv("OPENAI_API_KEY");
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
        System.out.println("🚀 Starting Transportation Declaration Task");
        System.out.println("📋 Task: Submit declaration for reactor fuel transport");
        System.out.println("   Route: Gdańsk → Żarnowiec");
        System.out.println("   Mass: 2800 kg (2.8 tons)");
        System.out.println("   Category: A (Strategic)");
        System.out.println("   Fee: 0 PP");
        
        // Based on documentation analysis:
        // - Żarnowiec route is excluded (Dyrektywa Specjalna 7.7)
        // - Only Category A and B can use excluded routes        // - Reactor fuel is Strategic (Category A)
        // - Category A is free (0 PP)
        // - From diagram: ŻARNOWIEC ===X=== GDAŃSK
        // - Route code: X-01 (most likely for primary excluded route)
        
        // Build the declaration
        System.out.println("\n📝 Building transportation declaration...");
        String declaration = buildDeclaration("X-01");
        System.out.println("\n" + declaration);
        
        // Submit to Hub
        System.out.println("\n📤 Submitting declaration to Hub...");
        if (AG3NTS_API_KEY == null || AG3NTS_API_KEY.isEmpty()) {
            System.err.println("❌ Error: AG3NTS_API_KEY not found in environment");
            System.err.println("   Please set AG3NTS_API_KEY in parent .env file or system environment");
            return;
        }
        submitDeclaration(declaration);
    }
   
    private static String downloadFile(String filename) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(DOC_BASE_URL + filename))
            .GET()
            .build();
            
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        
        if (response.statusCode() != 200) {
            throw new IOException("Failed to download " + filename + ": HTTP " + response.statusCode());
        }
        
        return response.body();
    }
    
    private static byte[] downloadBinaryFile(String filename) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(DOC_BASE_URL + filename))
            .GET()
            .build();
            
        HttpResponse<byte[]> response = HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray());
        
        if (response.statusCode() != 200) {
            throw new IOException("Failed to download " + filename + ": HTTP " + response.statusCode());
        }
        
        return response.body();
    }
    
    private static Set<String> findReferencedFiles(String content) {
        Set<String> files = new HashSet<>();
        
        Pattern markdownLink = Pattern.compile("\\[([^\\]]+)\\]\\(([^)]+)\\)");
        Matcher matcher = markdownLink.matcher(content);
        while (matcher.find()) {
            String link = matcher.group(2);
            if (!link.startsWith("http") && !link.startsWith("#")) {
                files.add(link);
            }
        }
        
        Pattern fileRef = Pattern.compile("([a-zA-Z0-9_-]+\\.(md|png|jpg|jpeg|txt|pdf))");
        matcher = fileRef.matcher(content);
        while (matcher.find()) {
            files.add(matcher.group(1));
        }
        
        return files;
    }
    
    private static String analyzeImageWithVision(Path imagePath) throws Exception {
        byte[] imageBytes = Files.readAllBytes(imagePath);
        String base64Image = Base64.getEncoder().encodeToString(imageBytes);
        
        Map<String, Object> request = new HashMap<>();
        request.put("model", "gpt-4o");
        request.put("messages", List.of(
            Map.of(
                "role", "user", 
                "content", List.of(
                    Map.of(
                        "type", "text",
                        "text", "This is an image showing excluded/blocked railway routes in the SPK (System Przesyłek Konduktorskich) network. I need to find the route code for the Gdańsk → Żarnowiec route. Please carefully read all route codes shown in this image and tell me which code corresponds to the Gdańsk-Żarnowiec route."
                    ),
                    Map.of(
                        "type", "image_url",
                        "image_url", Map.of(
                            "url", "data:image/png;base64," + base64Image
                        )
                    )
                )
            )
        ));
        request.put("max_tokens", 1000);
        
        String jsonBody = MAPPER.writeValueAsString(request);
        
        HttpRequest httpRequest = HttpRequest.newBuilder()
            .uri(URI.create("https://api.openai.com/v1/chat/completions"))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + OPENAI_API_KEY)
            .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
            .build();
            
        HttpResponse<String> response = HTTP.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        
        if (response.statusCode() != 200) {
            throw new IOException("Vision API error: " + response.statusCode() + " - " + response.body());
        }
        
        Map<String, Object> responseMap = MAPPER.readValue(response.body(), Map.class);
        List<Map<String, Object>> choices = (List<Map<String, Object>>) responseMap.get("choices");
        Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
        return (String) message.get("content");
    }
    
    private static String buildDeclaration(String routeCode) {
        // Based on the documentation:
        // Sender: 450202122
        // Route: Gdańsk → Żarnowiec
        // Category: A (Strategic - reactor fuel cassettes)
        // Mass: 2.8 tons = 2800 kg
        // WDP: (2800 - 1000) / 500 = 3.6 = 4additional wagons
        // Fee: 0 PP (Category A is free)
        
        LocalDate today = LocalDate.now();
        
        StringBuilder declaration = new StringBuilder();
        declaration.append("SYSTEM PRZESYŁEK KONDUKTORSKICH - DEKLARACJA ZAWARTOŚCI\n");
        declaration.append("======================================================\n");
        declaration.append("DATA: ").append(today).append("\n");
        declaration.append("PUNKT NADAWCZY: Gdańsk\n");
        declaration.append("------------------------------------------------------\n");
        declaration.append("NADAWCA: 450202122\n");
        declaration.append("PUNKT DOCELOWY: Żarnowiec\n");
        declaration.append("TRASA: ").append(routeCode).append("\n");
        declaration.append("------------------------------------------------------\n");
        declaration.append("KATEGORIA PRZESYŁKI: A\n");
        declaration.append("------------------------------------------------------\n");
        declaration.append("OPIS ZAWARTOŚCI (max 200 znaków): Kasety paliwowe reaktora\n");
        declaration.append("------------------------------------------------------\n");
        declaration.append("DEKLAROWANA MASA (kg): 2800\n");
        declaration.append("------------------------------------------------------\n");
        declaration.append("WDP: 4\n");
        declaration.append("------------------------------------------------------\n");
        declaration.append("UWAGI SPECJALNE:\n");
        declaration.append("------------------------------------------------------\n");
        declaration.append("KWOTA DO ZAPŁATY: 0 PP\n");
        declaration.append("------------------------------------------------------\n");
        declaration.append("OŚWIADCZAM, ŻE PODANE INFORMACJE SĄ PRAWDZIWE.\n");
        declaration.append("BIORĘ NA SIEBIE KONSEKWENCJĘ ZA FAŁSZYWE OŚWIADCZENIE.\n");
        declaration.append("======================================================\n");
        
        return declaration.toString();
    }
    
    private static void submitDeclaration(String declaration) throws Exception {
        Map<String, Object> request = new HashMap<>();
        request.put("task", "sendit");
        request.put("apikey", AG3NTS_API_KEY);
        request.put("answer", Map.of("declaration", declaration));
        
        String jsonBody = MAPPER.writeValueAsString(request);
        
        HttpRequest httpRequest = HttpRequest.newBuilder()
            .uri(URI.create("https://hub.ag3nts.org/verify"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
            .build();
            
        HttpResponse<String> response = HTTP.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        
        System.out.println("Response status: " + response.statusCode());
        System.out.println("Response body: " + response.body());
    }
}
