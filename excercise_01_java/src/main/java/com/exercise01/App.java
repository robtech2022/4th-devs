package com.exercise01;

import com.fasterxml.jackson.core.type.TypeReference;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class App {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private static final Path ROOT_DIR = Path.of(".").toAbsolutePath().normalize();
    private static final Path PROJECT_DIR = resolveProjectDir();
    private static final Path OUTPUT_FILE = PROJECT_DIR.resolve("output/people-answer.txt");

    private static final String TASK_NAME = "people";
    private static final String HUB_BASE_URL = "https://hub.ag3nts.org";
    private static final String TARGET_CITY = "grudziadz";
    private static final int CURRENT_YEAR = 2026;
    private static final int MIN_AGE = 20;
    private static final int MAX_AGE = 40;
    private static final int MIN_BORN_YEAR = CURRENT_YEAR - MAX_AGE;
    private static final int MAX_BORN_YEAR = CURRENT_YEAR - MIN_AGE;

    private static final List<String> TAGS = List.of(
            "IT", "transport", "edukacja", "medycyna", "praca z ludźmi", "praca z pojazdami", "praca fizyczna"
    );

    private static final Map<String, String> TAG_DESCRIPTIONS = Map.of(
            "IT", "Praca związana z oprogramowaniem, infrastrukturą IT, analizą danych lub cyberbezpieczeństwem.",
            "transport", "Logistyka, przewóz osób/towarów, spedycja, planowanie tras i łańcucha dostaw.",
            "edukacja", "Nauczanie, szkolenia, dydaktyka, mentoring i rozwój kompetencji.",
            "medycyna", "Opieka zdrowotna, diagnostyka, leczenie, pielęgniarstwo, ratownictwo.",
            "praca z ludźmi", "Obsługa klienta, doradztwo, HR, sprzedaż, role wymagające częstego kontaktu interpersonalnego.",
            "praca z pojazdami", "Prowadzenie, serwis, naprawa lub operowanie pojazdami i sprzętem transportowym.",
            "praca fizyczna", "Praca manualna lub terenowa, wymagająca wysiłku fizycznego."
    );

    public static void main(String[] args) throws Exception {
        loadEnvFile();

        String provider = resolveProvider();
        String endpoint = provider.equals("openrouter")
                ? "https://openrouter.ai/api/v1/chat/completions"
                : "https://api.openai.com/v1/chat/completions";
        String apiKey = provider.equals("openrouter") ? env("OPENROUTER_API_KEY") : env("OPENAI_API_KEY");
        String model = resolveModelForProvider(provider, "gpt-4o");

        System.out.println("[AI config] requested provider: " + Optional.ofNullable(System.getenv("AI_PROVIDER")).orElse("(auto)"));
        System.out.println("[AI config] resolved provider: " + provider);
        System.out.println("[AI config] chat endpoint: " + endpoint);

        String hubApiKey = firstNonBlank(env("AG3NTS_API_KEY"), env("HUB_API_KEY"));
        boolean autoSubmit = "true".equalsIgnoreCase(env("AG3NTS_AUTO_SUBMIT"));

        String csvText = fetchPeopleCsv(hubApiKey);
        List<Map<String, String>> rows = parseCsv(csvText);
        System.out.println("CSV data rows: " + rows.size());

        List<Person> people = rows.stream().map(App::mapPersonRecord).collect(Collectors.toList());
        List<Person> men = people.stream().filter(p -> "M".equals(p.gender)).toList();
        List<Person> menWithAge = men.stream().filter(p -> p.born != null && p.born >= MIN_BORN_YEAR && p.born <= MAX_BORN_YEAR).toList();
        List<Person> menWithAgeCity = menWithAge.stream().filter(p -> isBornInTargetCity(p.city)).toList();

        System.out.println("Men: " + men.size());
        System.out.println("Men age " + MIN_AGE + "-" + MAX_AGE + " in " + CURRENT_YEAR + ": " + menWithAge.size());
        System.out.println("Men age-matching and born in Grudziądz: " + menWithAgeCity.size());

        List<Person> candidates = people.stream().filter(App::isCandidate).collect(Collectors.toList());
        List<Person> tagged = tagJobs(candidates, endpoint, apiKey, provider, model);

        List<Map<String, Object>> answer = new ArrayList<>();
        for (Person p : tagged) {
            if (p.tags.contains("transport")) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("name", p.name);
                item.put("surname", p.surname);
                item.put("gender", p.gender);
                item.put("born", p.born);
                item.put("city", p.city);
                item.put("tags", p.tags);
                answer.add(item);
            }
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("apikey", hubApiKey);
        payload.put("task", TASK_NAME);
        payload.put("answer", answer);

        savePayloadToFile(payload);
        System.out.println("Candidates after demographic filtering: " + candidates.size());
        System.out.println("Final transport matches: " + answer.size());
        System.out.println("Saved payload: " + OUTPUT_FILE.toString());

        if (!autoSubmit) {
            System.out.println("Auto-submit is disabled. Set AG3NTS_AUTO_SUBMIT=true to submit automatically.");
            return;
        }

        JsonNode verifyResponse = submitAnswer(hubApiKey, answer);
        System.out.println("Verify response: " + MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(verifyResponse));
    }

    private static void loadEnvFile() {
        // no-op: Java cannot reliably mutate process environment variables at runtime.
        // We read .env lazily in env().
    }

    private static Path resolveProjectDir() {
        List<Path> candidates = List.of(
                ROOT_DIR.resolve("excercise_01_java"),
                ROOT_DIR.resolve("4th-devs/excercise_01_java"),
                ROOT_DIR.resolve("../excercise_01_java").normalize(),
                ROOT_DIR.resolve("../4th-devs/excercise_01_java").normalize()
        );

        for (Path candidate : candidates) {
            if (Files.exists(candidate.resolve("src/main/java/com/exercise01/App.java"))) {
                return candidate;
            }
        }

        return ROOT_DIR.resolve("excercise_01_java");
    }

    private static String env(String key) throws IOException {
        String val = System.getenv(key);
        if (val != null && !val.isBlank()) return val.trim();

        List<Path> candidates = List.of(
                ROOT_DIR.resolve(".env"),
                ROOT_DIR.resolve("4th-devs/.env"),
                ROOT_DIR.resolve("../.env").normalize(),
                ROOT_DIR.resolve("../../.env").normalize(),
                PROJECT_DIR.resolve("../.env").normalize(),
                PROJECT_DIR.resolve("../../.env").normalize()
        );

        for (Path envPath : candidates) {
            if (!Files.exists(envPath)) continue;
            for (String line : Files.readAllLines(envPath, StandardCharsets.UTF_8)) {
                String t = line.trim();
                if (t.startsWith(key + "=")) return t.substring((key + "=").length()).trim();
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
        if (requested.equals("openai") && !hasOpenAI) throw new RuntimeException("AI_PROVIDER=openai requires OPENAI_API_KEY");
        if (requested.equals("openrouter") && !hasOpenRouter) throw new RuntimeException("AI_PROVIDER=openrouter requires OPENROUTER_API_KEY");

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

    private static String firstNonBlank(String a, String b) {
        return (a != null && !a.isBlank()) ? a : (b != null ? b : "");
    }

    private static String normalizeText(String value) {
        if (value == null) return "";
        String n = Normalizer.normalize(value, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return n.trim().toLowerCase(Locale.ROOT);
    }

    private static Integer parseBornYear(String value) {
        if (value == null || value.isBlank()) return null;
        Matcher m = Pattern.compile("(19|20)\\d{2}").matcher(value);
        if (m.find()) return Integer.parseInt(m.group());
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static String normalizeGender(String value) {
        String n = normalizeText(value);
        if (Set.of("m", "male", "mezczyzna", "mężczyzna").contains(n)) return "M";
        if (Set.of("f", "female", "kobieta").contains(n)) return "F";
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static boolean isBornInTargetCity(String city) {
        String n = normalizeText(city);
        return n.equals(TARGET_CITY) || n.contains(TARGET_CITY);
    }

    private static boolean isCandidate(Person p) {
        return "M".equals(p.gender)
                && p.born != null
                && p.born >= MIN_BORN_YEAR
                && p.born <= MAX_BORN_YEAR
                && isBornInTargetCity(p.city);
    }

    private static Person mapPersonRecord(Map<String, String> raw) {
        return new Person(
                get(raw, "name", "imie"),
                get(raw, "surname", "nazwisko", "last_name"),
                normalizeGender(get(raw, "gender", "plec", "sex")),
                parseBornYear(get(raw, "born", "birth_year", "rok_urodzenia", "birthDate", "data_urodzenia")),
                get(raw, "city", "birth_city", "miasto", "miasto_urodzenia", "birthPlace", "miejsce_urodzenia"),
                get(raw, "job", "occupation", "stanowisko", "opis_pracy"),
                new ArrayList<>()
        );
    }

    private static String get(Map<String, String> raw, String... keys) {
        for (Map.Entry<String, String> entry : raw.entrySet()) {
            String rk = normalizeText(entry.getKey());
            for (String key : keys) {
                if (rk.equals(normalizeText(key))) {
                    return entry.getValue() == null ? "" : entry.getValue();
                }
            }
        }
        return "";
    }

    private static List<Map<String, String>> parseCsv(String csvText) {
        String[] lines = csvText.split("\\r?\\n");
        String firstLine = Arrays.stream(lines).filter(s -> !s.isBlank()).findFirst().orElse("");
        char delimiter = detectDelimiter(firstLine);

        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < csvText.length(); i++) {
            char c = csvText.charAt(i);
            char next = i + 1 < csvText.length() ? csvText.charAt(i + 1) : '\0';

            if (c == '"') {
                if (inQuotes && next == '"') {
                    field.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
                continue;
            }

            if (!inQuotes && c == delimiter) {
                row.add(field.toString());
                field.setLength(0);
                continue;
            }

            if (!inQuotes && (c == '\n' || c == '\r')) {
                if (c == '\r' && next == '\n') i++;
                row.add(field.toString());
                field.setLength(0);
                if (row.stream().anyMatch(v -> !v.trim().isEmpty())) rows.add(new ArrayList<>(row));
                row.clear();
                continue;
            }

            field.append(c);
        }

        if (field.length() > 0 || !row.isEmpty()) {
            row.add(field.toString());
            if (row.stream().anyMatch(v -> !v.trim().isEmpty())) rows.add(new ArrayList<>(row));
        }

        if (rows.isEmpty()) return List.of();
        List<String> header = rows.get(0).stream().map(s -> s.replace("\uFEFF", "").trim()).toList();

        List<Map<String, String>> result = new ArrayList<>();
        for (int i = 1; i < rows.size(); i++) {
            List<String> data = rows.get(i);
            Map<String, String> record = new LinkedHashMap<>();
            for (int j = 0; j < header.size(); j++) {
                record.put(header.get(j), j < data.size() ? data.get(j).trim() : "");
            }
            result.add(record);
        }
        return result;
    }

    private static char detectDelimiter(String line) {
        char[] candidates = new char[]{',', ';', '\t', '|'};
        int best = -1;
        char bestChar = ',';
        for (char c : candidates) {
            int count = 0;
            for (int i = 0; i < line.length(); i++) if (line.charAt(i) == c) count++;
            if (count > best) {
                best = count;
                bestChar = c;
            }
        }
        return best > 0 ? bestChar : ',';
    }

    private static String fetchPeopleCsv(String hubApiKey) throws IOException, InterruptedException {
        if (hubApiKey == null || hubApiKey.isBlank()) throw new RuntimeException("Missing AG3NTS_API_KEY (or HUB_API_KEY) in .env");
        String url = HUB_BASE_URL + "/data/" + URLEncoder.encode(hubApiKey, StandardCharsets.UTF_8) + "/people.csv";

        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).GET().build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new RuntimeException("Failed to fetch people.csv (status: " + response.statusCode() + ")");
        }
        return response.body();
    }

    private static List<Person> tagJobs(List<Person> candidates, String endpoint, String apiKey, String provider, String model) throws Exception {
        if (candidates.isEmpty()) return List.of();

        // build jobs list with stable index
        StringBuilder jobsBuilder = new StringBuilder();
        for (int i = 0; i < candidates.size(); i++) {
            jobsBuilder.append(i).append(": ").append(candidates.get(i).job == null || candidates.get(i).job.isBlank() ? "brak opisu" : candidates.get(i).job);
            if (i < candidates.size() - 1) jobsBuilder.append("\n");
        }

        String tagsGuide = TAGS.stream().map(t -> "- " + t + ": " + TAG_DESCRIPTIONS.get(t)).collect(Collectors.joining("\n"));
        String input = String.join("\n",
                "Otaguj opisy stanowisk pracy.",
                "Użyj wyłącznie tagów z listy i przypisz 0..N tagów do każdego rekordu.",
                "Nie zgaduj ponad opis; jeśli brak podstaw, zwróć pustą listę tagów.",
                "Tagi:",
                tagsGuide,
                "",
                "Rekordy:",
                jobsBuilder.toString()
        );

        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model", model);
        requestBody.put("messages", List.of(Map.of("role", "user", "content", input)));
        requestBody.put("response_format", Map.of(
                "type", "json_schema",
                "json_schema", Map.of(
                        "name", "job_tagging_results",
                        "strict", true,
                        "schema", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "results", Map.of(
                                                "type", "array",
                                                "items", Map.of(
                                                        "type", "object",
                                                        "properties", Map.of(
                                                                "id", Map.of("type", "integer"),
                                                                "tags", Map.of(
                                                                        "type", "array",
                                                                        "items", Map.of("type", "string", "enum", TAGS)
                                                                )
                                                        ),
                                                        "required", List.of("id", "tags"),
                                                        "additionalProperties", false
                                                )
                                        )
                                ),
                                "required", List.of("results"),
                                "additionalProperties", false
                        )
                )
        ));

        System.out.println("[tagJobs] Using endpoint: " + endpoint);

        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(endpoint))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey);

        if (provider.equals("openrouter")) {
            String referer = env("OPENROUTER_HTTP_REFERER");
            String appName = env("OPENROUTER_APP_NAME");
            if (!referer.isBlank()) rb.header("HTTP-Referer", referer);
            if (!appName.isBlank()) rb.header("X-Title", appName);
        }

        HttpRequest request = rb.POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(requestBody), StandardCharsets.UTF_8)).build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        JsonNode data;
        try {
            data = MAPPER.readTree(response.body());
        } catch (Exception e) {
            data = MAPPER.createObjectNode();
        }

        if (response.statusCode() < 200 || response.statusCode() >= 300 || data.has("error")) {
            String msg = data.path("error").path("message").asText(response.body().substring(0, Math.min(300, response.body().length())));
            throw new RuntimeException("[tagJobs][" + provider + "] " + msg + " (status: " + response.statusCode() + ", model: " + model + ")");
        }

        String content = data.path("choices").path(0).path("message").path("content").asText("");
        if (content.isBlank()) throw new RuntimeException("Missing output in API response");

        Map<String, Object> parsed = MAPPER.readValue(content, new TypeReference<>() {});
        Object resultsObj = parsed.get("results");
        if (!(resultsObj instanceof List<?> results)) {
            throw new RuntimeException("Invalid tagging result: missing results array");
        }

        Map<Integer, List<String>> idToTags = new HashMap<>();
        for (Object obj : results) {
            if (obj instanceof Map<?, ?> m) {
                Object id = m.get("id");
                Object tags = m.get("tags");
                if (id instanceof Number num && tags instanceof List<?> tagList) {
                    idToTags.put(num.intValue(), tagList.stream().map(String::valueOf).toList());
                }
            }
        }

        List<Person> out = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            Person p = candidates.get(i);
            p.tags = new ArrayList<>(idToTags.getOrDefault(i, List.of()));
            out.add(p);
        }
        return out;
    }

    private static void savePayloadToFile(Map<String, Object> payload) throws IOException {
        Files.createDirectories(OUTPUT_FILE.getParent());
        Files.writeString(OUTPUT_FILE, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(payload) + System.lineSeparator(), StandardCharsets.UTF_8);
    }

    private static JsonNode submitAnswer(String hubApiKey, List<Map<String, Object>> answer) throws Exception {
        Map<String, Object> payload = Map.of(
                "apikey", hubApiKey,
                "task", TASK_NAME,
                "answer", answer
        );

        HttpRequest request = HttpRequest.newBuilder(URI.create(HUB_BASE_URL + "/verify"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(payload), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new RuntimeException("Verify request failed (status: " + response.statusCode() + ")");
        }
        return MAPPER.readTree(response.body());
    }

    private static class Person {
        String name;
        String surname;
        String gender;
        Integer born;
        String city;
        String job;
        List<String> tags;

        Person(String name, String surname, String gender, Integer born, String city, String job, List<String> tags) {
            this.name = name;
            this.surname = surname;
            this.gender = gender;
            this.born = born;
            this.city = city;
            this.job = job;
            this.tags = tags;
        }
    }
}
