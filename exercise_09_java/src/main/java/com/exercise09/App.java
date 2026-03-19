package com.exercise09;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public class App {

    static final String ZMAIL_URL = "https://hub.ag3nts.org/api/zmail";
    static final String VERIFY_URL = "https://hub.ag3nts.org/verify";
    static final String OPENROUTER_URL = "https://openrouter.ai/api/v1/chat/completions";
    static final String MODEL = "google/gemini-2.0-flash-001";

    static String API_KEY;
    static String OPENROUTER_KEY;

    static final HttpClient http = HttpClient.newHttpClient();
    static final ObjectMapper om = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        API_KEY = loadEnv("AG3NTS_API_KEY");
        OPENROUTER_KEY = loadEnv("OPENROUTER_API_KEY");

        System.out.println("=== Mailbox Intelligence Agent ===");

        List<Map<String, Object>> tools = buildTools();

        String systemPrompt = """
                You are an intelligence agent investigating a threat against a power plant.

                Your goal is to find three pieces of information from an email inbox:
                1. date (YYYY-MM-DD) - when the security department plans to attack the power plant
                2. password - the employee system password found in the mailbox
                3. confirmation_code - a security ticket code exactly in format SEC-XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX
                   (SEC- followed by exactly 32 characters = 36 total)

                AVAILABLE API ACTIONS (POST to zmail):
                - getInbox: list inbox threads (params: page, perPage=20 for more results)
                - getMessages: get FULL email with body (params: ids = rowID integer or 32-char messageID)
                - getThread: list thread message IDs (params: threadID) - NO body included
                - search: search emails (params: query with Gmail operators from:, to:, subject:, OR, AND)
                - reset: reset rate limit counter

                CRITICAL RULES:
                - Use getMessages with ids=<rowID> to read email body
                - Search "pracowniczego" to find password email ("New employee password")
                - Search "SEC-" to find security ticket emails
                - READ ALL EMAILS IN A THREAD - confirmation codes may be corrected in follow-up emails
                - If a code/value looks incomplete or someone mentions a correction, read other emails in that thread
                - The inbox is live - retry searches if needed

                WORKFLOW:
                1. Search for SEC- emails to find attack date and confirmation code
                2. Read ALL emails in the SEC- thread (some may have corrections like "I made a typo, correct code is...")
                3. Search "pracowniczego" to find password email
                4. Verify confirmation_code is exactly SEC- + 32 characters (36 total)
                5. Submit with all three confirmed values

                HANDLING -970 error: means confirmation_code format is wrong (not 36 chars total).
                Check if any email says the code was wrong and provides a corrected version.

                HANDLING -960 error: means values are correct format but content is wrong. Search more.
                """;

        List<Object> messages = new ArrayList<>();
        messages.add(Map.of(
                "role", "user",
                "content", "Investigate the mailbox. Search for SEC- emails (read ALL of them for corrections), find the employee password (search pracowniczego), then submit."
        ));

        int maxIter = 40;
        for (int i = 0; i < maxIter; i++) {
            System.out.println("\n--- Iteration " + (i + 1) + "/" + maxIter + " ---");

            JsonNode response = callLLM(systemPrompt, messages, tools);
            JsonNode choice = response.path("choices").path(0);
            JsonNode message = choice.path("message");
            String finishReason = choice.path("finish_reason").asText("");

            String textContent = message.path("content").asText(null);
            if (textContent != null && !textContent.isBlank()) {
                System.out.println("Agent: " + textContent.substring(0, Math.min(400, textContent.length())));
            }

            JsonNode toolCalls = message.path("tool_calls");

            if (toolCalls.isArray() && !toolCalls.isEmpty()) {
                messages.add(buildAssistantMessage(message));

                for (JsonNode toolCall : toolCalls) {
                    String callId = toolCall.path("id").asText();
                    String toolName = toolCall.path("function").path("name").asText();
                    String argsJson = toolCall.path("function").path("arguments").asText("{}");

                    System.out.println("  -> " + toolName + "(" + argsJson + ")");
                    String result = executeTool(toolName, argsJson);
                    System.out.println("  <- " + result.substring(0, Math.min(1200, result.length())));

                    if ("submit_answer".equals(toolName)) {
                        try {
                            JsonNode resJson = om.readTree(result);
                            String resMessage = resJson.path("message").asText("");
                            if (resMessage.contains("FLG:") || result.contains("FLG:")) {
                                System.out.println("\n*** SUCCESS! Flag: " + resMessage + " ***");
                                return;
                            }
                            int code = resJson.path("code").asInt(-1);
                            if (code == -970) {
                                System.out.println("Submit feedback: -970 = confirmation_code format wrong. Must be SEC- + exactly 32 chars. Check for correction emails.");
                            } else {
                                System.out.println("Submit feedback: " + resMessage + " (code: " + code + ")");
                            }
                        } catch (Exception e) {
                            System.out.println("Failed to parse verify response: " + result);
                        }
                    }

                    messages.add(Map.of(
                            "role", "tool",
                            "tool_call_id", callId,
                            "content", result
                    ));
                }
            } else if ("stop".equals(finishReason)) {
                System.out.println("Agent stopped. Nudging...");
                messages.add(buildAssistantMessage(message));
                messages.add(Map.of(
                        "role", "user",
                        "content", "Continue. Need: date (YYYY-MM-DD), password, confirmation_code (SEC-+32chars=36total). Use getMessages ids=<rowID> to read email content. Check ALL emails in SEC- thread for corrections."
                ));
            } else {
                System.out.println("Finish: " + finishReason);
                break;
            }
        }
        System.out.println("Max iterations reached.");
    }

    static List<Map<String, Object>> buildTools() {
        return List.of(
                Map.of(
                        "type", "function",
                        "function", Map.of(
                                "name", "call_zmail",
                                "description", "Call zmail API. Use getMessages with ids=<rowID> to read full email body. Use search to find emails. Use getInbox to browse. Use reset to clear rate limits.",
                                "parameters", Map.of(
                                        "type", "object",
                                        "properties", Map.of(
                                                "action", Map.of(
                                                        "type", "string",
                                                        "description", "Action: getInbox, getMessages, getThread, search, reset"
                                                ),
                                                "page", Map.of(
                                                        "type", "integer",
                                                        "description", "Page for getInbox/search"
                                                ),
                                                "perPage", Map.of(
                                                        "type", "integer",
                                                        "description", "Results per page (5-20)"
                                                ),
                                                "query", Map.of(
                                                        "type", "string",
                                                        "description", "Search query. Gmail operators: from:, to:, subject:, OR, AND"
                                                ),
                                                "ids", Map.of(
                                                        "type", "string",
                                                        "description", "For getMessages: rowID number or 32-char messageID to read full email body"
                                                ),
                                                "threadID", Map.of(
                                                        "type", "integer",
                                                        "description", "For getThread: numeric threadID"
                                                )
                                        ),
                                        "required", List.of("action")
                                )
                        )
                ),
                Map.of(
                        "type", "function",
                        "function", Map.of(
                                "name", "submit_answer",
                                "description", "Submit answer. Requires date (YYYY-MM-DD), password, confirmation_code (SEC- + exactly 32 chars = 36 total). -970 error means code is wrong length.",
                                "parameters", Map.of(
                                        "type", "object",
                                        "properties", Map.of(
                                                "date", Map.of("type", "string", "description", "Attack date YYYY-MM-DD"),
                                                "password", Map.of("type", "string", "description", "Employee system password"),
                                                "confirmation_code", Map.of("type", "string", "description", "SEC- followed by exactly 32 hex characters (36 total)")
                                        ),
                                        "required", List.of("date", "password", "confirmation_code")
                                )
                        )
                )
        );
    }

    static String executeTool(String toolName, String argsJson) throws Exception {
        JsonNode args = om.readTree(argsJson);

        if ("call_zmail".equals(toolName)) {
            ObjectNode body = om.createObjectNode();
            body.put("apikey", API_KEY);
            String action = args.path("action").asText("getInbox");
            body.put("action", action);

            if (args.has("page")) body.put("page", args.path("page").asInt(1));
            if (args.has("perPage")) body.put("perPage", args.path("perPage").asInt(5));
            if (args.has("query")) body.put("query", args.path("query").asText());
            if (args.has("threadID")) body.put("threadID", args.path("threadID").asInt());

            if (args.has("ids")) {
                JsonNode idsNode = args.path("ids");
                if (idsNode.isArray()) {
                    body.set("ids", idsNode);
                } else {
                    String idsVal = idsNode.asText();
                    try {
                        body.put("ids", Integer.parseInt(idsVal));
                    } catch (NumberFormatException e) {
                        body.put("ids", idsVal);
                    }
                }
            }

            Thread.sleep(2000);
            return postJson(ZMAIL_URL, om.writeValueAsString(body));
        }

        if ("submit_answer".equals(toolName)) {
            ObjectNode body = om.createObjectNode();
            body.put("apikey", API_KEY);
            body.put("task", "mailbox");
            ObjectNode answer = om.createObjectNode();
            answer.put("password", args.path("password").asText());
            answer.put("date", args.path("date").asText());
            answer.put("confirmation_code", args.path("confirmation_code").asText());
            body.set("answer", answer);
            String result = postJson(VERIFY_URL, om.writeValueAsString(body));
            System.out.println("VERIFY RESPONSE: " + result);
            return result;
        }

        return "{\"error\": \"Unknown tool: " + toolName + "\"}";
    }

    static String postJson(String url, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        return response.body();
    }

    static JsonNode callLLM(String systemPrompt, List<Object> messages, List<Map<String, Object>> tools) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", MODEL);

        List<Object> allMessages = new ArrayList<>();
        allMessages.add(Map.of("role", "system", "content", systemPrompt));
        allMessages.addAll(messages);
        body.put("messages", allMessages);
        body.put("tools", tools);
        body.put("tool_choice", "auto");

        String bodyJson = om.writeValueAsString(body);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(OPENROUTER_URL))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + OPENROUTER_KEY)
                .POST(HttpRequest.BodyPublishers.ofString(bodyJson, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode result = om.readTree(response.body());

        JsonNode error = result.path("error");
        if (!error.isMissingNode() && !error.isNull()) {
            throw new RuntimeException("LLM API error: " + error.path("message").asText(response.body()));
        }

        return result;
    }

    static Map<String, Object> buildAssistantMessage(JsonNode message) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("role", "assistant");

        JsonNode contentNode = message.path("content");
        if (contentNode.isNull() || contentNode.isMissingNode()) {
            msg.put("content", null);
        } else {
            msg.put("content", contentNode.asText(null));
        }

        JsonNode toolCalls = message.path("tool_calls");
        if (toolCalls.isArray() && !toolCalls.isEmpty()) {
            List<Map<String, Object>> tcList = new ArrayList<>();
            for (JsonNode tc : toolCalls) {
                Map<String, Object> tcMap = new LinkedHashMap<>();
                tcMap.put("id", tc.path("id").asText());
                tcMap.put("type", "function");
                tcMap.put("function", Map.of(
                        "name", tc.path("function").path("name").asText(),
                        "arguments", tc.path("function").path("arguments").asText("{}")
                ));
                tcList.add(tcMap);
            }
            msg.put("tool_calls", tcList);
        }

        return msg;
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
        throw new RuntimeException(key + " not found in environment or .env file");
    }
}