package com.exercise02;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

public class App {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private static final String TASK_NAME = "findhim";
    private static final String HUB_BASE_URL = "https://hub.ag3nts.org";
    private static final Pattern PLANT_CODE_PATTERN = Pattern.compile("PWR\\d{4}PL");
    private static final int MAX_AGENT_ITERATIONS = 15;

    private static final Path ROOT_DIR = Path.of(".").toAbsolutePath().normalize();
    private static final Path PROJECT_DIR = resolveProjectDir();
    private static final Path OUTPUT_FILE = PROJECT_DIR.resolve("output/findhim-answer.txt");
    private static final Path LOCATIONS_FILE = PROJECT_DIR.resolve("output/findhim_locations.json");
    private static final Map<String, Coordinate> KNOWN_CITY_COORDINATES = buildKnownCityCoordinates();

    public static void main(String[] args) throws Exception {
        String hubApiKey = firstNonBlank(env("AG3NTS_API_KEY"), env("HUB_API_KEY"));
        if (hubApiKey.isBlank()) {
            throw new RuntimeException("Missing AG3NTS_API_KEY (or HUB_API_KEY) in .env");
        }

        boolean autoSubmit = "true".equalsIgnoreCase(env("AG3NTS_AUTO_SUBMIT"));
        String provider = resolveProvider();
        String model = resolveModelForProvider(provider, firstNonBlank(env("AGENT_MODEL"), "gpt-5-mini"));
        String chatEndpoint = provider.equals("openrouter")
                ? "https://openrouter.ai/api/v1/chat/completions"
                : "https://api.openai.com/v1/chat/completions";
        String aiApiKey = provider.equals("openrouter") ? env("OPENROUTER_API_KEY") : env("OPENAI_API_KEY");

        System.out.println("[AI config] provider: " + provider);
        System.out.println("[AI config] model: " + model);
        System.out.println("[AI config] endpoint: " + chatEndpoint);

        List<Suspect> suspects = loadSuspectsFromPreviousTask();
        if (suspects.isEmpty()) {
            throw new RuntimeException("No suspects found. Run task 1 first and keep output/people-answer.txt.");
        }

        JsonNode locationsJson = loadLocationsJson(hubApiKey);
        Map<String, Coordinate> plants = extractPowerPlants(locationsJson);
        if (plants.isEmpty()) {
            throw new RuntimeException("No power plants parsed from findhim_locations.json");
        }

        System.out.println("Suspects loaded: " + suspects.size());
        System.out.println("Power plants loaded: " + plants.size());

        AgentContext context = new AgentContext(
                hubApiKey,
                autoSubmit,
                suspects,
                plants,
                provider,
                model,
                chatEndpoint,
                aiApiKey
        );

        runFunctionCallingAgent(context);

        if (context.finalPayload == null) {
            throw new RuntimeException("Agent finished without submitting a final answer payload");
        }

        System.out.println("Saved payload: " + OUTPUT_FILE);
        if (context.verifyResponse != null) {
            System.out.println("Verify response:");
            System.out.println(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(context.verifyResponse));
        }
    }

    private static void runFunctionCallingAgent(AgentContext ctx) throws Exception {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of(
                "role", "system",
                "content", "Jesteś agentem śledczym dla zadania findhim. Używaj wyłącznie narzędzi. Najpierw ustal podejrzanego najbliżej elektrowni, następnie pobierz accessLevel i wywołaj submit_findhim_answer. Nie odpowiadaj finalnie tekstem bez wywołania submit_findhim_answer."
        ));
        messages.add(Map.of(
                "role", "user",
                "content", "Znajdź właściwą osobę, kod elektrowni i poziom dostępu. Użyj narzędzi i zakończ przez submit_findhim_answer."
        ));

        List<Map<String, Object>> tools = buildTools();

        for (int i = 0; i < MAX_AGENT_ITERATIONS; i++) {
            Map<String, Object> requestBody = new LinkedHashMap<>();
            requestBody.put("model", ctx.model);
            requestBody.put("messages", messages);
            requestBody.put("tools", tools);
            requestBody.put("tool_choice", "auto");
            requestBody.put("temperature", 0);

            JsonNode data = fetchJson(
                    "POST",
                    ctx.chatEndpoint,
                    requestBody,
                    buildAiHeaders(ctx)
            );

            JsonNode messageNode = data.path("choices").path(0).path("message");
            if (messageNode.isMissingNode()) {
                throw new RuntimeException("Missing assistant message in model response");
            }

            Map<String, Object> assistantMessage = new LinkedHashMap<>();
            assistantMessage.put("role", "assistant");
            if (!messageNode.path("content").isMissingNode() && !messageNode.path("content").isNull()) {
                assistantMessage.put("content", messageNode.path("content").asText(""));
            }
            if (messageNode.has("tool_calls")) {
                assistantMessage.put("tool_calls", MAPPER.convertValue(messageNode.path("tool_calls"), Object.class));
            }
            messages.add(assistantMessage);

            JsonNode toolCalls = messageNode.path("tool_calls");
            if (!toolCalls.isArray() || toolCalls.isEmpty()) {
                if (ctx.finalPayload != null) {
                    return;
                }

                String text = messageNode.path("content").asText("");
                throw new RuntimeException("Agent returned text without tool calls before final submission: " + text);
            }

            for (JsonNode call : toolCalls) {
                String toolCallId = call.path("id").asText();
                String functionName = call.path("function").path("name").asText();
                String argumentsJson = call.path("function").path("arguments").asText("{}");

                String toolResult = executeTool(ctx, functionName, argumentsJson);

                Map<String, Object> toolMessage = new LinkedHashMap<>();
                toolMessage.put("role", "tool");
                toolMessage.put("tool_call_id", toolCallId);
                toolMessage.put("content", toolResult);
                messages.add(toolMessage);
            }

            if (ctx.finalPayload != null) {
                return;
            }
        }

        throw new RuntimeException("Agent exceeded max iterations: " + MAX_AGENT_ITERATIONS);
    }

    private static Map<String, String> buildAiHeaders(AgentContext ctx) throws IOException {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Authorization", "Bearer " + ctx.aiApiKey);

        if ("openrouter".equals(ctx.provider)) {
            String referer = env("OPENROUTER_HTTP_REFERER");
            String appName = env("OPENROUTER_APP_NAME");
            if (!referer.isBlank()) {
                headers.put("HTTP-Referer", referer);
            }
            if (!appName.isBlank()) {
                headers.put("X-Title", appName);
            }
        }

        return headers;
    }

    private static List<Map<String, Object>> buildTools() {
        return List.of(
                tool("list_suspects", "Zwraca listę podejrzanych z poprzedniego zadania.", Map.of(
                        "type", "object",
                        "properties", Map.of(),
                        "additionalProperties", false
                )),
                tool("list_power_plants", "Zwraca listę elektrowni i ich współrzędne.", Map.of(
                        "type", "object",
                        "properties", Map.of(),
                        "additionalProperties", false
                )),
                tool("find_closest_plant_for_person", "Dla podanej osoby zwraca najbliższą elektrownię, dystans oraz listę obserwacji.", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "name", Map.of("type", "string"),
                                "surname", Map.of("type", "string"),
                                "birthYear", Map.of("type", "integer")
                        ),
                        "required", List.of("name", "surname", "birthYear"),
                        "additionalProperties", false
                )),
                tool("get_access_level", "Pobiera accessLevel dla osoby z endpointu /api/accesslevel.", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "name", Map.of("type", "string"),
                                "surname", Map.of("type", "string"),
                                "birthYear", Map.of("type", "integer")
                        ),
                        "required", List.of("name", "surname", "birthYear"),
                        "additionalProperties", false
                )),
                tool("submit_findhim_answer", "Zapisuje finalny payload i opcjonalnie wysyła go na /verify.", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "name", Map.of("type", "string"),
                                "surname", Map.of("type", "string"),
                                "accessLevel", Map.of("type", "integer"),
                                "powerPlant", Map.of("type", "string", "pattern", "PWR\\d{4}PL")
                        ),
                        "required", List.of("name", "surname", "accessLevel", "powerPlant"),
                        "additionalProperties", false
                ))
        );
    }

    private static Map<String, Object> tool(String name, String description, Map<String, Object> parameters) {
        Map<String, Object> fn = new LinkedHashMap<>();
        fn.put("name", name);
        fn.put("description", description);
        fn.put("parameters", parameters);

        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("type", "function");
        tool.put("function", fn);
        return tool;
    }

    private static String executeTool(AgentContext ctx, String functionName, String argumentsJson) throws Exception {
        JsonNode args;
        try {
            args = MAPPER.readTree(argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson);
        } catch (Exception ex) {
            args = MAPPER.createObjectNode();
        }

        switch (functionName) {
            case "list_suspects" -> {
                return MAPPER.writeValueAsString(Map.of("suspects", ctx.suspects));
            }
            case "list_power_plants" -> {
                List<Map<String, Object>> list = new ArrayList<>();
                for (Map.Entry<String, Coordinate> e : ctx.powerPlants.entrySet()) {
                    list.add(Map.of(
                            "code", e.getKey(),
                            "latitude", e.getValue().latitude(),
                            "longitude", e.getValue().longitude()
                    ));
                }
                return MAPPER.writeValueAsString(Map.of("powerPlants", list));
            }
            case "find_closest_plant_for_person" -> {
                String name = args.path("name").asText("").trim();
                String surname = args.path("surname").asText("").trim();
                int birthYear = args.path("birthYear").asInt(Integer.MIN_VALUE);

                Suspect suspect = findSuspect(ctx.suspects, name, surname, birthYear);
                List<Coordinate> seenLocations = fetchLocationsForPerson(suspect, ctx.hubApiKey);
                Match nearest = nearestPlantForLocations(suspect, seenLocations, ctx.powerPlants);
                if (nearest == null) {
                    return MAPPER.writeValueAsString(Map.of("found", false));
                }

                Map<String, Object> out = new LinkedHashMap<>();
                out.put("found", true);
                out.put("name", suspect.name());
                out.put("surname", suspect.surname());
                out.put("birthYear", suspect.born());
                out.put("powerPlant", nearest.powerPlantCode());
                out.put("distanceKm", nearest.distanceKm());
                return MAPPER.writeValueAsString(out);
            }
            case "get_access_level" -> {
                String name = args.path("name").asText("").trim();
                String surname = args.path("surname").asText("").trim();
                int birthYear = args.path("birthYear").asInt(Integer.MIN_VALUE);
                Suspect suspect = findSuspect(ctx.suspects, name, surname, birthYear);
                int accessLevel = fetchAccessLevel(suspect, ctx.hubApiKey);
                return MAPPER.writeValueAsString(Map.of(
                        "name", suspect.name(),
                        "surname", suspect.surname(),
                        "birthYear", suspect.born(),
                        "accessLevel", accessLevel
                ));
            }
            case "submit_findhim_answer" -> {
                String name = args.path("name").asText("").trim();
                String surname = args.path("surname").asText("").trim();
                int accessLevel = args.path("accessLevel").asInt(Integer.MIN_VALUE);
                String powerPlant = args.path("powerPlant").asText("").trim();

                if (!PLANT_CODE_PATTERN.matcher(powerPlant).matches()) {
                    throw new RuntimeException("powerPlant must match PWR0000PL format");
                }
                if (accessLevel == Integer.MIN_VALUE) {
                    throw new RuntimeException("Missing accessLevel");
                }

                Map<String, Object> answer = new LinkedHashMap<>();
                answer.put("name", name);
                answer.put("surname", surname);
                answer.put("accessLevel", accessLevel);
                answer.put("powerPlant", powerPlant);

                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("apikey", ctx.hubApiKey);
                payload.put("task", TASK_NAME);
                payload.put("answer", answer);

                savePayload(payload);
                ctx.finalPayload = payload;

                if (ctx.autoSubmit) {
                    ctx.verifyResponse = fetchJson("POST", HUB_BASE_URL + "/verify", payload, Map.of("Content-Type", "application/json"));
                }

                return MAPPER.writeValueAsString(Map.of(
                        "saved", true,
                        "autoSubmitted", ctx.autoSubmit
                ));
            }
            default -> throw new RuntimeException("Unknown tool: " + functionName);
        }
    }

    private static Suspect findSuspect(List<Suspect> suspects, String name, String surname, int birthYear) {
        return suspects.stream()
                .filter(s -> s.name().equalsIgnoreCase(name)
                        && s.surname().equalsIgnoreCase(surname)
                        && s.born() == birthYear)
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Suspect not found in previous task data: " + name + " " + surname + " " + birthYear));
    }

    private static Match nearestPlantForLocations(Suspect suspect, List<Coordinate> seenLocations, Map<String, Coordinate> plants) {
        Match best = null;
        for (Coordinate seen : seenLocations) {
            for (Map.Entry<String, Coordinate> plant : plants.entrySet()) {
                double distance = haversineKm(seen.latitude(), seen.longitude(), plant.getValue().latitude(), plant.getValue().longitude());
                if (best == null || distance < best.distanceKm()) {
                    best = new Match(suspect, plant.getKey(), distance);
                }
            }
        }
        return best;
    }

    private static List<Coordinate> fetchLocationsForPerson(Suspect suspect, String apiKey) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("apikey", apiKey);
        payload.put("name", suspect.name());
        payload.put("surname", suspect.surname());

        JsonNode response = fetchJson(
                "POST",
                HUB_BASE_URL + "/api/location",
                payload,
                Map.of("Content-Type", "application/json")
        );
        List<Coordinate> coords = new ArrayList<>();
        extractCoordinates(response, coords);
        return coords;
    }

    private static int fetchAccessLevel(Suspect suspect, String apiKey) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("apikey", apiKey);
        payload.put("name", suspect.name());
        payload.put("surname", suspect.surname());
        payload.put("birthYear", suspect.born());

        JsonNode response = fetchJson(
                "POST",
                HUB_BASE_URL + "/api/accesslevel",
                payload,
                Map.of("Content-Type", "application/json")
        );

        JsonNode explicit = response.path("accessLevel");
        if (explicit.isInt()) {
            return explicit.asInt();
        }

        Integer fallback = findFirstInt(response);
        if (fallback != null) {
            return fallback;
        }

        throw new RuntimeException("Cannot parse accessLevel for " + suspect.name() + " " + suspect.surname());
    }

    private static Integer findFirstInt(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }

        if (node.isInt()) {
            return node.asInt();
        }

        if (node.isTextual()) {
            String text = node.asText().trim();
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }

        if (node.isArray()) {
            for (JsonNode item : node) {
                Integer found = findFirstInt(item);
                if (found != null) {
                    return found;
                }
            }
        }

        if (node.isObject()) {
            Iterator<JsonNode> it = node.elements();
            while (it.hasNext()) {
                Integer found = findFirstInt(it.next());
                if (found != null) {
                    return found;
                }
            }
        }

        return null;
    }

    private static JsonNode fetchJson(String method, String url, Map<String, Object> payload, Map<String, String> headers) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url));
        if (headers != null) {
            for (Map.Entry<String, String> h : headers.entrySet()) {
                builder.header(h.getKey(), h.getValue());
            }
        }

        if ("POST".equalsIgnoreCase(method)) {
            String body = MAPPER.writeValueAsString(payload == null ? Map.of() : payload);
            builder.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        } else {
            builder.GET();
        }

        HttpResponse<String> response = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        String raw = response.body() == null ? "" : response.body();

        JsonNode data;
        try {
            data = MAPPER.readTree(raw);
        } catch (Exception ex) {
            data = MAPPER.createObjectNode();
        }

        if (response.statusCode() < 200 || response.statusCode() >= 300 || data.has("error")) {
            String msg = data.path("error").path("message").asText(raw.length() > 300 ? raw.substring(0, 300) : raw);
            throw new RuntimeException(msg + " (status: " + response.statusCode() + ", url: " + url + ")");
        }

        return data;
    }

    private static JsonNode loadLocationsJson(String hubApiKey) throws Exception {
        if (Files.exists(LOCATIONS_FILE)) {
            String raw = Files.readString(LOCATIONS_FILE, StandardCharsets.UTF_8);
            return MAPPER.readTree(raw);
        }

        String remoteUrl = HUB_BASE_URL + "/data/" + URLEncoder.encode(hubApiKey, StandardCharsets.UTF_8) + "/findhim_locations.json";
        return fetchJson("GET", remoteUrl, null, null);
    }

    private static Map<String, Coordinate> extractPowerPlants(JsonNode root) {
        Map<String, Coordinate> plants = new LinkedHashMap<>();
        extractPowerPlantsRecursive(root, plants);
        return plants;
    }

    private static void extractPowerPlantsRecursive(JsonNode node, Map<String, Coordinate> out) {
        if (node == null || node.isNull()) {
            return;
        }

        if (node.isObject()) {
            // Case 1: key is plant code and value contains coordinates.
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> e = fields.next();
                String key = e.getKey();
                JsonNode value = e.getValue();

                if (PLANT_CODE_PATTERN.matcher(key).matches()) {
                    Coordinate c = tryCoordinate(value);
                    if (c != null) {
                        out.put(key, c);
                    }
                }

                // Case 1b: key is city name and value contains a plant code but no coordinates.
                if (value != null && value.isObject()) {
                    String code = value.path("code").asText("").trim();
                    if (PLANT_CODE_PATTERN.matcher(code).matches()) {
                        Coordinate c = tryCoordinate(value);
                        if (c == null) {
                            c = coordinateForCity(key);
                        }
                        if (c != null) {
                            out.put(code, c);
                        }
                    }
                }
            }

            // Case 2: object has code field + location fields.
            String code = findPlantCode(node);
            Coordinate c = tryCoordinate(node);
            if (code != null && c != null) {
                out.put(code, c);
            }

            Iterator<JsonNode> children = node.elements();
            while (children.hasNext()) {
                extractPowerPlantsRecursive(children.next(), out);
            }
            return;
        }

        if (node.isArray()) {
            for (JsonNode item : node) {
                extractPowerPlantsRecursive(item, out);
            }
        }
    }

    private static String findPlantCode(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }

        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> e = fields.next();
            if (e.getValue().isTextual()) {
                String text = e.getValue().asText().trim();
                if (PLANT_CODE_PATTERN.matcher(text).matches()) {
                    return text;
                }
            }
        }
        return null;
    }

    private static Coordinate tryCoordinate(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }

        if (node.isObject()) {
            Double lat = findDoubleByKeys(node, "lat", "latitude", "y");
            Double lon = findDoubleByKeys(node, "lon", "lng", "longitude", "x");
            if (lat != null && lon != null) {
                return new Coordinate(lat, lon);
            }

            JsonNode coords = node.path("coordinates");
            Coordinate nested = tryCoordinate(coords);
            if (nested != null) {
                return nested;
            }
        }

        if (node.isArray() && node.size() >= 2 && node.get(0).isNumber() && node.get(1).isNumber()) {
            return new Coordinate(node.get(0).asDouble(), node.get(1).asDouble());
        }

        if (node.isTextual()) {
            String[] parts = node.asText().split(",");
            if (parts.length == 2) {
                try {
                    return new Coordinate(Double.parseDouble(parts[0].trim()), Double.parseDouble(parts[1].trim()));
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }

        return null;
    }

    private static Coordinate coordinateForCity(String city) {
        if (city == null || city.isBlank()) {
            return null;
        }
        return KNOWN_CITY_COORDINATES.get(normalizeCity(city));
    }

    private static String normalizeCity(String city) {
        String normalized = Normalizer.normalize(city, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .trim();
        return normalized;
    }

    private static Map<String, Coordinate> buildKnownCityCoordinates() {
        Map<String, Coordinate> map = new HashMap<>();
        map.put(normalizeCity("Zabrze"), new Coordinate(50.3249, 18.7857));
        map.put(normalizeCity("Piotrków Trybunalski"), new Coordinate(51.4052, 19.7030));
        map.put(normalizeCity("Grudziądz"), new Coordinate(53.4841, 18.7537));
        map.put(normalizeCity("Tczew"), new Coordinate(54.0924, 18.7779));
        map.put(normalizeCity("Radom"), new Coordinate(51.4027, 21.1471));
        map.put(normalizeCity("Chełmno"), new Coordinate(53.3486, 18.4259));
        map.put(normalizeCity("Chelmno"), new Coordinate(53.3486, 18.4259));
        map.put(normalizeCity("Żarnowiec"), new Coordinate(54.7388, 18.0846));
        map.put(normalizeCity("Zarnowiec"), new Coordinate(54.7388, 18.0846));
        return map;
    }

    private static Double findDoubleByKeys(JsonNode node, String... keys) {
        for (String key : keys) {
            JsonNode val = node.get(key);
            if (val != null && val.isNumber()) {
                return val.asDouble();
            }
            if (val != null && val.isTextual()) {
                try {
                    return Double.parseDouble(val.asText().trim());
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return null;
    }

    private static void extractCoordinates(JsonNode node, List<Coordinate> out) {
        if (node == null || node.isNull()) {
            return;
        }

        Coordinate c = tryCoordinate(node);
        if (c != null) {
            out.add(c);
        }

        if (node.isObject()) {
            Iterator<JsonNode> it = node.elements();
            while (it.hasNext()) {
                extractCoordinates(it.next(), out);
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                extractCoordinates(child, out);
            }
        }
    }

    private static double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        double earthRadiusKm = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return earthRadiusKm * c;
    }

    private static List<Suspect> loadSuspectsFromPreviousTask() throws Exception {
        List<Path> candidates = List.of(
                ROOT_DIR.resolve("exercise_01_js/output/people-answer.txt"),
                ROOT_DIR.resolve("exercise_01_python/output/people-answer.txt"),
                ROOT_DIR.resolve("excercise_01_java/output/people-answer.txt"),
                ROOT_DIR.resolve("01_01_structured/output/people-answer.txt"),
                ROOT_DIR.resolve("4th-devs/exercise_01_js/output/people-answer.txt"),
                ROOT_DIR.resolve("4th-devs/exercise_01_python/output/people-answer.txt"),
                ROOT_DIR.resolve("4th-devs/excercise_01_java/output/people-answer.txt"),
                ROOT_DIR.resolve("4th-devs/01_01_structured/output/people-answer.txt")
        );

        Path input = null;
        for (Path path : candidates) {
            if (Files.exists(path)) {
                input = path;
                break;
            }
        }

        if (input == null) {
            return List.of();
        }

        JsonNode root = MAPPER.readTree(Files.readString(input, StandardCharsets.UTF_8));
        JsonNode answer = root.path("answer");
        if (!answer.isArray()) {
            return List.of();
        }

        List<Suspect> suspects = new ArrayList<>();
        for (JsonNode item : answer) {
            String name = item.path("name").asText("").trim();
            String surname = item.path("surname").asText("").trim();
            Integer born = parseBirthYear(item.path("born"));

            if (!name.isEmpty() && !surname.isEmpty() && born != null) {
                suspects.add(new Suspect(name, surname, born));
            }
        }

        return suspects;
    }

    private static Integer parseBirthYear(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }

        if (node.isInt()) {
            return node.asInt();
        }

        if (node.isTextual()) {
            String text = node.asText().trim();
            if (text.isEmpty()) {
                return null;
            }

            java.util.regex.Matcher m = Pattern.compile("(19|20)\\d{2}").matcher(text);
            if (m.find()) {
                return Integer.parseInt(m.group());
            }
        }

        return null;
    }

    private static void savePayload(Map<String, Object> payload) throws Exception {
        Files.createDirectories(OUTPUT_FILE.getParent());
        Files.writeString(OUTPUT_FILE, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(payload) + System.lineSeparator(), StandardCharsets.UTF_8);
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        return b == null ? "" : b;
    }

    private static String env(String key) throws IOException {
        String val = System.getenv(key);
        if (val != null && !val.isBlank()) {
            return val.trim();
        }

        List<Path> envCandidates = List.of(
                ROOT_DIR.resolve(".env"),
                ROOT_DIR.resolve("4th-devs/.env"),
                ROOT_DIR.resolve("../.env").normalize(),
                ROOT_DIR.resolve("../../.env").normalize(),
                PROJECT_DIR.resolve("../.env").normalize(),
                PROJECT_DIR.resolve("../../.env").normalize()
        );

        for (Path envPath : envCandidates) {
            if (!Files.exists(envPath)) {
                continue;
            }

            for (String line : Files.readAllLines(envPath, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.startsWith(key + "=")) {
                    return trimmed.substring((key + "=").length()).trim();
                }
            }
        }

        return "";
    }

    private static String resolveProvider() throws IOException {
        String requested = env("AI_PROVIDER").toLowerCase(Locale.ROOT);
        boolean hasOpenAI = !env("OPENAI_API_KEY").isBlank();
        boolean hasOpenRouter = !env("OPENROUTER_API_KEY").isBlank();

        if (!requested.isBlank() && !Set.of("openai", "openrouter").contains(requested)) {
            throw new RuntimeException("AI_PROVIDER must be one of: openai, openrouter");
        }
        if (requested.equals("openai") && !hasOpenAI) {
            throw new RuntimeException("AI_PROVIDER=openai requires OPENAI_API_KEY");
        }
        if (requested.equals("openrouter") && !hasOpenRouter) {
            throw new RuntimeException("AI_PROVIDER=openrouter requires OPENROUTER_API_KEY");
        }

        if (!requested.isBlank()) return requested;
        if (hasOpenAI) return "openai";
        if (hasOpenRouter) return "openrouter";
        throw new RuntimeException("No API key set. Add OPENAI_API_KEY or OPENROUTER_API_KEY");
    }

    private static String resolveModelForProvider(String provider, String model) {
        if (provider.equals("openrouter") && !model.contains("/") && model.startsWith("gpt-")) {
            return "openai/" + model;
        }
        return model;
    }

    private static Path resolveProjectDir() {
        List<Path> candidates = List.of(
                ROOT_DIR.resolve("exercise_02_java"),
                ROOT_DIR.resolve("4th-devs/exercise_02_java"),
                ROOT_DIR.resolve("../exercise_02_java").normalize(),
                ROOT_DIR.resolve("../4th-devs/exercise_02_java").normalize()
        );

        for (Path candidate : candidates) {
            if (Files.exists(candidate.resolve("src/main/java/com/exercise02/App.java"))) {
                return candidate;
            }
        }

        return ROOT_DIR.resolve("exercise_02_java");
    }

    private record Coordinate(double latitude, double longitude) {}

    private record Suspect(String name, String surname, int born) {}

    private record Match(Suspect suspect, String powerPlantCode, double distanceKm) {}

    private static final class AgentContext {
        final String hubApiKey;
        final boolean autoSubmit;
        final List<Suspect> suspects;
        final Map<String, Coordinate> powerPlants;
        final String provider;
        final String model;
        final String chatEndpoint;
        final String aiApiKey;

        Map<String, Object> finalPayload;
        JsonNode verifyResponse;

        AgentContext(
                String hubApiKey,
                boolean autoSubmit,
                List<Suspect> suspects,
                Map<String, Coordinate> powerPlants,
                String provider,
                String model,
                String chatEndpoint,
                String aiApiKey
        ) {
            this.hubApiKey = hubApiKey;
            this.autoSubmit = autoSubmit;
            this.suspects = suspects;
            this.powerPlants = powerPlants;
            this.provider = provider;
            this.model = model;
            this.chatEndpoint = chatEndpoint;
            this.aiApiKey = aiApiKey;
        }
    }
}
