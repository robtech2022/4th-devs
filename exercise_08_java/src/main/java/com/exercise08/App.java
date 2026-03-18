package com.exercise08;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.*;

public class App {

    static final String BASE = "https://hub.ag3nts.org";
    static final int TOKEN_LIMIT = 1350;

    static String API_KEY;
    static String OPENROUTER_KEY;

    static final HttpClient http = HttpClient.newHttpClient();
    static final ObjectMapper om = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        API_KEY = loadEnv("AG3NTS_API_KEY");
        OPENROUTER_KEY = loadEnv("OPENROUTER_API_KEY");
        if (API_KEY.isBlank()) throw new RuntimeException("AG3NTS_API_KEY is required");
        if (OPENROUTER_KEY.isBlank()) throw new RuntimeException("OPENROUTER_API_KEY is required");

        System.out.println("=== Power Plant Failure Log Analyzer ===");
        String rawLog = downloadLog();
        String[] allLines = rawLog.split("\r?\n");
        System.out.println("Total lines: " + allLines.length);

        List<String> importantLines = filterImportantLines(allLines);
        System.out.println("CRIT/ERRO/WARN: " + importantLines.size() + " lines");

        System.out.println("Compressing with LLM...");
        String compressed = compressWithLLM(String.join("\n", importantLines));
        compressed = cleanAndSort(compressed);
        System.out.println("Start: " + compressed.split("\\n").length + " lines, " + estimateTokens(compressed) + " tokens");

        agentLoop(compressed, importantLines, allLines);
    }

    static void agentLoop(String initialLog, List<String> importantLines, String[] allLines) throws Exception {
        LinkedHashMap<String, String> logSet = new LinkedHashMap<>();
        for (String line : initialLog.split("\n")) {
            String t = line.trim();
            if (!t.isBlank() && t.startsWith("[20")) logSet.put(t, t);
        }

        for (int iter = 1; iter <= 20; iter++) {
            String currentLog = buildSortedLog(logSet);
            System.out.println("\n--- Iter " + iter + " | " + logSet.size() + " lines | " + estimateTokens(currentLog) + " tokens ---");

            if (estimateTokens(currentLog) > TOKEN_LIMIT) {
                System.out.println("Compressing...");
                currentLog = compressWithLLM(currentLog);
                currentLog = cleanAndSort(currentLog);
                logSet.clear();
                for (String line : currentLog.split("\n")) {
                    String t = line.trim();
                    if (!t.isBlank() && t.startsWith("[20")) logSet.put(t, t);
                }
                System.out.println("After compress: " + logSet.size() + " lines | " + estimateTokens(buildSortedLog(logSet)) + " tokens");
                currentLog = buildSortedLog(logSet);
            }

            String response = submitAnswer(currentLog);
            System.out.println("Response: " + response);

            JsonNode json = om.readTree(response);
            String code = json.has("code") ? json.get("code").asText() : "";
            String message = json.has("message") ? json.get("message").asText() : "";

            if (message.contains("FLG:") || code.equals("0")) {
                System.out.println("\n*** SUCCESS: " + message + " ***");
                return;
            }

            if (code.equals("-940")) {
                System.out.println("Too many tokens - compressing harder...");
                currentLog = compressWithLLM(currentLog);
                currentLog = cleanAndSort(currentLog);
                logSet.clear();
                for (String line : currentLog.split("\n")) {
                    String t = line.trim();
                    if (!t.isBlank() && t.startsWith("[20")) logSet.put(t, t);
                }
                continue;
            }

            if (code.equals("-960")) {
                // Too short - add more CRIT/ERRO lines from importantLines
                System.out.println("Too short - adding more lines from original log...");
                List<String> critLines = importantLines.stream()
                        .filter(l -> l.contains("[CRIT]") || l.contains("[ERRO]"))
                        .limit(50)
                        .collect(java.util.stream.Collectors.toList());
                for (String line : critLines) logSet.put(line.trim(), line.trim());
                System.out.println("Added up to 50 CRIT/ERRO lines, now: " + logSet.size());
                continue;
            }

            if (code.equals("-944")) {
                System.out.println("Order issue - removing INFO lines...");
                String offending = extractQuotedLine(message);
                if (offending != null && offending.contains("[INFO]")) {
                    System.out.println("  Removing: " + offending);
                    logSet.remove(offending.trim());
                }
                continue;
            }

            if (code.equals("-948") || code.equals("-949")) {
                String component = extractComponent(message);
                if (component != null) {
                    System.out.println("Missing: " + component);
                    List<String> found = findComponentLines(component, importantLines, allLines);
                    System.out.println("  Found " + found.size() + " lines");
                    List<String> toAdd = selectBestLines(found, 5);
                    for (String line : toAdd) logSet.put(line.trim(), line.trim());
                    System.out.println("  Added " + toAdd.size() + " for " + component);
                }
                continue;
            }

            if (code.equals("-947")) {
                String offending = extractQuotedLine(message);
                System.out.println("No time marker: " + offending);
                if (offending != null) logSet.remove(offending.trim());
                continue;
            }

            System.out.println("Unknown code: " + code);
        }
        System.out.println("Max iterations reached.");
    }

    static String buildSortedLog(LinkedHashMap<String, String> logSet) {
        return logSet.values().stream().sorted(Comparator.naturalOrder()).collect(Collectors.joining("\n"));
    }

    static String cleanAndSort(String log) {
        return Arrays.stream(log.replace("\r\n", "\n").replace("\r", "\n").split("\n"))
                .filter(l -> !l.isBlank())
                .filter(l -> l.trim().startsWith("[20"))
                .filter(l -> !l.contains("[INFO]"))
                .sorted(Comparator.naturalOrder())
                .distinct()
                .collect(Collectors.joining("\n"));
    }

    static String loadEnv(String key) throws Exception {
        String envVal = System.getenv(key);
        if (envVal != null && !envVal.isBlank()) return envVal.trim();

        Path cwd = Path.of(".").toAbsolutePath().normalize();
        List<Path> candidates = List.of(
                cwd.resolve(".env"),
                cwd.resolve("../.env").normalize(),
                cwd.resolve("../../.env").normalize(),
                cwd.resolve("../../../.env").normalize()
        );
        for (Path envPath : candidates) {
            if (!Files.exists(envPath)) continue;
            for (String line : Files.readAllLines(envPath, StandardCharsets.UTF_8)) {
                String t = line.trim();
                if (t.startsWith("#") || !t.contains("=")) continue;
                if (t.startsWith(key + "=")) return t.substring(key.length() + 1).trim();
            }
        }
        return "";
    }

    static String downloadLog() throws Exception {
        var resp = http.send(HttpRequest.newBuilder()
                .uri(URI.create(BASE + "/data/" + API_KEY + "/failure.log"))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        return resp.body();
    }

    static List<String> filterImportantLines(String[] lines) {
        List<String> result = new ArrayList<>();
        for (String line : lines) {
            if (line.isBlank()) continue;
            if (!line.contains("[CRIT]") && !line.contains("[ERRO]") && !line.contains("[WARN]")) continue;
            result.add(line.trim());
        }
        return result;
    }

    static int estimateTokens(String text) {
        return (int) Math.ceil(text.length() / 3.0);
    }

    static String compressWithLLM(String log) throws Exception {
        String prompt = "You are a log compression assistant for a power plant failure analysis.\\n\\n" +
                "Compress these log entries (WARN/ERRO/CRIT) to fit within 1350 tokens total.\\n" +
                "Rules:\\n" +
                "- Keep only power plant failure relevant events\\n" +
                "  (power, cooling, water, reactor, turbines, steam, software, electrical, firmware)\\n" +
                "- One line per event: [YYYY-MM-DD HH:MM] [LEVEL] COMPONENT description\\n" +
                "- Keep timestamp, severity, component ID\\n" +
                "- Priority: CRIT > ERRO > WARN\\n" +
                "- Remove duplicates/repetitive (keep first)\\n" +
                "- Chronological order\\n" +
                "- NO markdown, backticks, or extra text\\n" +
                "- Return ONLY log lines, ~30-40 max\\n\\n" +
                "ENTRIES:\\n" + log;
        return callLLM(prompt);
    }

    static String callLLM(String userPrompt) throws Exception {
        ObjectNode body = om.createObjectNode();
        body.put("model", "gpt-4o-mini");
        ArrayNode messages = om.createArrayNode();
        ObjectNode msg = om.createObjectNode();
        msg.put("role", "user");
        msg.put("content", userPrompt);
        messages.add(msg);
        body.set("messages", messages);
        body.put("max_tokens", 2000);
        body.put("temperature", 0.1);

        var resp = http.send(HttpRequest.newBuilder()
                .uri(URI.create("https://openrouter.ai/api/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + OPENROUTER_KEY)
                .header("HTTP-Referer", "https://github.com/ai_devs")
                .header("X-Title", "exercise08")
                .POST(HttpRequest.BodyPublishers.ofString(om.writeValueAsString(body)))
                .build(), HttpResponse.BodyHandlers.ofString());

        JsonNode json = om.readTree(resp.body());
        String raw = json.at("/choices/0/message/content").asText();
        return raw.replace("\r\n", "\n").replace("\r", "\n")
                  .replaceAll("(?m)^```[a-zA-Z]*\\s*\n?", "")
                  .replaceAll("(?m)^```\\s*\n?", "")
                  .strip();
    }

    static String submitAnswer(String logsContent) throws Exception {
        ObjectNode payload = om.createObjectNode();
        payload.put("apikey", API_KEY);
        payload.put("task", "failure");
        ObjectNode answer = om.createObjectNode();
        answer.put("logs", logsContent);
        payload.set("answer", answer);

        var resp = http.send(HttpRequest.newBuilder()
                .uri(URI.create(BASE + "/verify"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(om.writeValueAsString(payload)))
                .build(), HttpResponse.BodyHandlers.ofString());
        return resp.body();
    }

    static String extractComponent(String message) {
        Matcher m = Pattern.compile("to (?:device )?([A-Z][A-Z0-9_]*)[\\.!]").matcher(message);
        return m.find() ? m.group(1) : null;
    }

    static String extractQuotedLine(String message) {
        Matcher m = Pattern.compile("\"([^\"]+)\"\\.").matcher(message);
        return m.find() ? m.group(1) : null;
    }

    static List<String> findComponentLines(String component, List<String> importantLines, String[] allLines) {
        String cl = component.toLowerCase();
        List<String> found = importantLines.stream().filter(l -> l.toLowerCase().contains(cl)).collect(Collectors.toList());
        if (found.isEmpty()) {
            found = Arrays.stream(allLines)
                    .filter(l -> l.toLowerCase().contains(cl))
                    .filter(l -> l.contains("[CRIT]") || l.contains("[ERRO]") || l.contains("[WARN]"))
                    .map(String::trim).collect(Collectors.toList());
        }
        if (found.isEmpty()) {
            String prefix = component.replaceAll("[0-9]+$", "").toLowerCase();
            if (!prefix.isEmpty() && !prefix.equals(cl)) {
                found = importantLines.stream().filter(l -> l.toLowerCase().contains(prefix)).collect(Collectors.toList());
            }
        }
        return found;
    }

    static List<String> selectBestLines(List<String> lines, int max) {
        List<String> r = new ArrayList<>();
        for (String l : lines) if (l.contains("[CRIT]") && r.size() < max) r.add(l);
        for (String l : lines) if (l.contains("[ERRO]") && r.size() < max) r.add(l);
        for (String l : lines) if (l.contains("[WARN]") && r.size() < max) r.add(l);
        return r;
    }
}