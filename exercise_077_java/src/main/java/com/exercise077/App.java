package com.exercise077;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Electricity puzzle solver.
 * 
 * Mapping derived from experimental rotation cycles + LLM vision analysis:
 * - Cycle A (bends): 0=TR, 3=RB, 6=BL, 7=TL  (cycle: 0→3→6→7→0)
 * - Cycle B (T-junctions): 2=TRB, 5=RBL, 8=TBL, 9=TRL  (cycle: 2→5→8→9→2)
 * - Cycle C (straights): 1=TB, 4=RL  (cycle: 1→4→1→4)
 * 
 * Grid tile types (from initial state [[3,2,1],[4,5,5],[8,7,0]]):
 *   bend(A) | T-junc(B) | straight(C)
 *   straight(C) | T-junc(B) | T-junc(B)
 *   T-junc(B) | bend(A)   | bend(A)
 */
public class App {

    static final String BASE = "https://hub.ag3nts.org";

    static String API_KEY;
    static String OPENROUTER_KEY;

    static final HttpClient http = HttpClient.newHttpClient();
    static final ObjectMapper om = new ObjectMapper();

    // Bitmask encoding: T=8, R=4, B=2, L=1
    static final int T = 8, R = 4, B = 2, L = 1;

    // Tile rotations (CW order)
    static final int[] BEND = {9, 12, 6, 3};       // TL, TR, RB, BL
    static final int[] TJUNC = {13, 14, 7, 11};     // TRL, TRB, RBL, TBL
    static final int[] STRAIGHT = {10, 5};           // TB, RL

    // JSON value cycles (experimentally determined)
    static final int[] CYCLE_A = {0, 3, 6, 7};      // bends
    static final int[] CYCLE_B = {2, 5, 8, 9};      // T-junctions
    static final int[] CYCLE_C = {1, 4};             // straights

    // Which cycle each cell belongs to (row-major order)
    // From initial grid [[3,2,1],[4,5,5],[8,7,0]]
    static final int[] CELL_CYCLE = {0, 1, 2,  2, 1, 1,  1, 0, 0}; // 0=A, 1=B, 2=C

    static final String[] BM_NAMES = {
        "-", "L", "B", "BL", "R", "RL", "RB", "RBL",
        "T", "TL", "TB", "TBL", "TR", "TRL", "TRB", "TRBL"
    };

    // --- Hub API ---

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

    static void resetBoard() throws Exception {
        http.send(HttpRequest.newBuilder()
                .uri(URI.create(BASE + "/data/" + API_KEY + "/electricity.png?reset=1"))
                .GET().build(), HttpResponse.BodyHandlers.discarding());
    }

    static int[][] fetchGrid() throws Exception {
        var resp = http.send(HttpRequest.newBuilder()
                .uri(URI.create(BASE + "/data/" + API_KEY + "/electricity.json"))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        return om.readValue(resp.body(), int[][].class);
    }

    static String sendRotation(String cell) throws Exception {
        String body = om.writeValueAsString(Map.of(
                "apikey", API_KEY, "task", "electricity",
                "answer", Map.of("rotate", cell)));
        var resp = http.send(HttpRequest.newBuilder()
                .uri(URI.create(BASE + "/verify"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
        return resp.body();
    }

    // --- OpenRouter LLM ---

    static String callVisionLLM(String prompt, String... imageUrls) throws Exception {
        ArrayNode messages = om.createArrayNode();
        ObjectNode userMsg = om.createObjectNode();
        userMsg.put("role", "user");
        ArrayNode content = om.createArrayNode();
        for (String url : imageUrls) {
            ObjectNode imgPart = om.createObjectNode();
            imgPart.put("type", "image_url");
            ObjectNode imgUrl = om.createObjectNode();
            imgUrl.put("url", url);
            imgPart.set("image_url", imgUrl);
            content.add(imgPart);
        }
        ObjectNode textPart = om.createObjectNode();
        textPart.put("type", "text");
        textPart.put("text", prompt);
        content.add(textPart);
        userMsg.set("content", content);
        messages.add(userMsg);

        ObjectNode reqBody = om.createObjectNode();
        reqBody.put("model", "google/gemini-3-flash-preview");
        reqBody.set("messages", messages);
        reqBody.put("max_tokens", 4000);

        var resp = http.send(HttpRequest.newBuilder()
                .uri(URI.create("https://openrouter.ai/api/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + OPENROUTER_KEY)
                .POST(HttpRequest.BodyPublishers.ofString(om.writeValueAsString(reqBody)))
                .build(), HttpResponse.BodyHandlers.ofString());

        JsonNode respJson = om.readTree(resp.body());
        if (respJson.has("choices") && respJson.get("choices").size() > 0)
            return respJson.get("choices").get(0).get("message").get("content").asText();
        System.out.println("LLM error: " + resp.body());
        return "";
    }

    // --- Solver helpers ---

    static int posInCycle(int[] cycle, int val) {
        for (int i = 0; i < cycle.length; i++) if (cycle[i] == val) return i;
        return -1;
    }

    static int jsonToBitmask(int jsonVal, int[] jsonCycle, int[] bmCycle, int alignment) {
        int pos = posInCycle(jsonCycle, jsonVal);
        return bmCycle[(pos + alignment) % bmCycle.length];
    }

    static int bitmaskToJsonCyclePos(int bm, int[] bmCycle, int alignment) {
        for (int i = 0; i < bmCycle.length; i++) {
            if (bmCycle[i] == bm) {
                return (i - alignment + bmCycle.length) % bmCycle.length;
            }
        }
        return -1;
    }

    static boolean edgesMatch(int[][] bitmaskGrid) {
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) {
                int v = bitmaskGrid[r][c];
                if (c < 2 && ((v & R) != 0) != ((bitmaskGrid[r][c+1] & L) != 0)) return false;
                if (r < 2 && ((v & B) != 0) != ((bitmaskGrid[r+1][c] & T) != 0)) return false;
            }
        }
        return true;
    }

    static boolean isFullyConnected(int[][] bm) {
        boolean[][] visited = new boolean[3][3];
        Queue<int[]> q = new LinkedList<>();
        q.add(new int[]{0, 0});
        visited[0][0] = true;
        int count = 1;
        int[][] dirs = {{0,1,R,L}, {0,-1,L,R}, {1,0,B,T}, {-1,0,T,B}};
        while (!q.isEmpty()) {
            int[] cur = q.poll();
            for (int[] d : dirs) {
                int nr = cur[0]+d[0], nc = cur[1]+d[1];
                if (nr >= 0 && nr < 3 && nc >= 0 && nc < 3 && !visited[nr][nc]
                    && (bm[cur[0]][cur[1]] & d[2]) != 0 && (bm[nr][nc] & d[3]) != 0) {
                    visited[nr][nc] = true;
                    q.add(new int[]{nr, nc});
                    count++;
                }
            }
        }
        return count == 9;
    }

    // Backtracking solver: find target bitmask configurations
    static void findSolutions(int[][] bmOptions, int[][] sol, int pos, List<int[][]> results) {
        if (pos == 9) {
            if (isFullyConnected(sol)) {
                int[][] copy = new int[3][3];
                for (int i = 0; i < 3; i++) copy[i] = sol[i].clone();
                results.add(copy);
            }
            return;
        }
        int r = pos / 3, c = pos % 3;
        for (int bm : bmOptions[pos]) {
            sol[r][c] = bm;
            // Check partial constraints
            if (c > 0 && ((bm & L) != 0) != ((sol[r][c-1] & R) != 0)) continue;
            if (r > 0 && ((bm & T) != 0) != ((sol[r-1][c] & B) != 0)) continue;
            findSolutions(bmOptions, sol, pos + 1, results);
        }
    }

    static void printBitmaskGrid(String label, int[][] bm) {
        System.out.println(label);
        for (int r = 0; r < 3; r++)
            System.out.printf("  %4s | %4s | %4s%n", BM_NAMES[bm[r][0]], BM_NAMES[bm[r][1]], BM_NAMES[bm[r][2]]);
    }

    // --- Main ---

    public static void main(String[] args) throws Exception {
        API_KEY = loadEnv("AG3NTS_API_KEY");
        OPENROUTER_KEY = loadEnv("OPENROUTER_API_KEY");
        if (API_KEY.isBlank()) throw new RuntimeException("AG3NTS_API_KEY is required");
        if (OPENROUTER_KEY.isBlank()) throw new RuntimeException("OPENROUTER_API_KEY is required");

        System.out.println("=== Resetting board ===");
        resetBoard();
        int[][] grid = fetchGrid();
        System.out.println("Initial JSON: " + Arrays.deepToString(grid));

        // Step 1: Find all valid connected target configurations
        System.out.println("\n=== Finding valid target configurations ===");
        int[][] bmOptions = new int[9][];
        for (int pos = 0; pos < 9; pos++) {
            bmOptions[pos] = switch (CELL_CYCLE[pos]) {
                case 0 -> BEND;
                case 1 -> TJUNC;
                case 2 -> STRAIGHT;
                default -> throw new RuntimeException();
            };
        }
        List<int[][]> solutions = new ArrayList<>();
        findSolutions(bmOptions, new int[3][3], 0, solutions);
        System.out.println("Found " + solutions.size() + " valid connected configurations");

        // Filter: power source enters from left of 3x1, so 3x1 must have L
        solutions.removeIf(s -> (s[2][0] & L) == 0);
        System.out.println("After filtering (3x1 must have L): " + solutions.size());

        for (int i = 0; i < solutions.size(); i++)
            printBitmaskGrid("Solution " + (i + 1) + ":", solutions.get(i));

        if (solutions.isEmpty()) {
            System.out.println("ERROR: No valid solutions!");
            return;
        }

        // Step 2: Use LLM to analyze solved image and pick the right solution
        System.out.println("\n=== Asking LLM to analyze solved image ===");
        String solvedUrl = BASE + "/i/solved_electricity.png";
        String solvedAnalysis = callVisionLLM("""
            Analyze this SOLVED electrical grid puzzle image. It shows a 3x3 grid of cable tiles.
            For each cell, identify which edges have cable connections.
            T=Top, R=Right, B=Bottom, L=Left.
            
            Grid positions:
            1x1 | 1x2 | 1x3
            2x1 | 2x2 | 2x3
            3x1 | 3x2 | 3x3
            
            Respond ONLY with lines like: 1x1=EDGES (e.g. 1x1=RB or 2x2=TRB)
            Sort edge letters in T,R,B,L order.
            """, solvedUrl);
        System.out.println("LLM solved analysis:\n" + solvedAnalysis);

        // Parse LLM analysis into bitmasks
        Map<String, Integer> llmTarget = new HashMap<>();
        for (String line : solvedAnalysis.split("\n")) {
            line = line.trim();
            if (line.matches("\\d+x\\d+=.*")) {
                String[] parts = line.split("=");
                String edges = parts[1].trim().toUpperCase();
                int bm = 0;
                if (edges.contains("T")) bm |= T;
                if (edges.contains("R")) bm |= R;
                if (edges.contains("B")) bm |= B;
                if (edges.contains("L")) bm |= L;
                llmTarget.put(parts[0].trim(), bm);
            }
        }
        System.out.println("LLM target bitmasks: " + llmTarget);

        // Score each solution against LLM analysis
        int bestScore = -1;
        int[][] bestSolution = null;
        for (int[][] sol : solutions) {
            int score = 0;
            for (int r = 0; r < 3; r++) {
                for (int c = 0; c < 3; c++) {
                    String key = (r+1) + "x" + (c+1);
                    Integer llmBm = llmTarget.get(key);
                    if (llmBm != null && llmBm == sol[r][c]) score++;
                }
            }
            if (score > bestScore) {
                bestScore = score;
                bestSolution = sol;
            }
        }
        printBitmaskGrid("Best matching solution (score=" + bestScore + "/9):", bestSolution);

        // Step 3: Try all 16 alignment combos for the best solution
        // For each (dA, dB), compute rotations needed
        System.out.println("\n=== Trying alignment combinations ===");

        for (int dA = 0; dA < 4; dA++) {
            for (int dB = 0; dB < 4; dB++) {
                // dC is fixed: we verified 1=TB, 4=RL → dC=0
                int dC = 0;

                // Verify: compute current bitmask state with this alignment
                int[][] curBm = new int[3][3];
                for (int pos = 0; pos < 9; pos++) {
                    int r = pos / 3, c = pos % 3;
                    curBm[r][c] = switch (CELL_CYCLE[pos]) {
                        case 0 -> jsonToBitmask(grid[r][c], CYCLE_A, BEND, dA);
                        case 1 -> jsonToBitmask(grid[r][c], CYCLE_B, TJUNC, dB);
                        case 2 -> jsonToBitmask(grid[r][c], CYCLE_C, STRAIGHT, dC);
                        default -> throw new RuntimeException();
                    };
                }

                // Compute rotations from current to target
                int[][] rotCounts = new int[3][3];
                int totalRots = 0;
                for (int pos = 0; pos < 9; pos++) {
                    int r = pos / 3, c = pos % 3;
                    int[] jsonCycle = switch (CELL_CYCLE[pos]) {
                        case 0 -> CYCLE_A; case 1 -> CYCLE_B; case 2 -> CYCLE_C;
                        default -> throw new RuntimeException();
                    };
                    int[] bmCycle = switch (CELL_CYCLE[pos]) {
                        case 0 -> BEND; case 1 -> TJUNC; case 2 -> STRAIGHT;
                        default -> throw new RuntimeException();
                    };
                    int curJsonPos = posInCycle(jsonCycle, grid[r][c]);
                    int targetJsonPos = bitmaskToJsonCyclePos(bestSolution[r][c], bmCycle, switch (CELL_CYCLE[pos]) {
                        case 0 -> dA; case 1 -> dB; case 2 -> dC; default -> throw new RuntimeException();
                    });
                    int cycleLen = jsonCycle.length;
                    rotCounts[r][c] = (targetJsonPos - curJsonPos + cycleLen) % cycleLen;
                    totalRots += rotCounts[r][c];
                }

                if (totalRots == 0) continue;

                System.out.printf("dA=%d, dB=%d: %d rotations → ", dA, dB, totalRots);
                for (int r = 0; r < 3; r++)
                    for (int c = 0; c < 3; c++)
                        if (rotCounts[r][c] > 0)
                            System.out.printf("%dx%d×%d ", r+1, c+1, rotCounts[r][c]);
                System.out.println();

                // Apply
                resetBoard();
                String lastResp = "";
                boolean flagFound = false;
                for (int r = 0; r < 3 && !flagFound; r++) {
                    for (int c = 0; c < 3 && !flagFound; c++) {
                        String cell = (r+1) + "x" + (c+1);
                        for (int i = 0; i < rotCounts[r][c]; i++) {
                            lastResp = sendRotation(cell);
                            if (lastResp.contains("FLG")) {
                                System.out.println("\n*** FLAG FOUND: " + lastResp + " ***");
                                flagFound = true;
                                break;
                            }
                        }
                    }
                }
                if (flagFound) return;
            }
        }

        // Step 4: If best solution didn't work, try all other solutions
        System.out.println("\n=== Trying other solutions ===");
        for (int[][] sol : solutions) {
            if (sol == bestSolution) continue;
            printBitmaskGrid("Trying:", sol);

            for (int dA = 0; dA < 4; dA++) {
                for (int dB = 0; dB < 4; dB++) {
                    int dC = 0;
                    int[][] rotCounts = new int[3][3];
                    int totalRots = 0;
                    for (int pos = 0; pos < 9; pos++) {
                        int r = pos / 3, c = pos % 3;
                        int[] jsonCycle = switch (CELL_CYCLE[pos]) {
                            case 0 -> CYCLE_A; case 1 -> CYCLE_B; case 2 -> CYCLE_C;
                            default -> throw new RuntimeException();
                        };
                        int[] bmCycle = switch (CELL_CYCLE[pos]) {
                            case 0 -> BEND; case 1 -> TJUNC; case 2 -> STRAIGHT;
                            default -> throw new RuntimeException();
                        };
                        int curJsonPos = posInCycle(jsonCycle, grid[r][c]);
                        int targetJsonPos = bitmaskToJsonCyclePos(sol[r][c], bmCycle, switch (CELL_CYCLE[pos]) {
                            case 0 -> dA; case 1 -> dB; case 2 -> dC; default -> throw new RuntimeException();
                        });
                        int cycleLen = jsonCycle.length;
                        rotCounts[r][c] = (targetJsonPos - curJsonPos + cycleLen) % cycleLen;
                        totalRots += rotCounts[r][c];
                    }
                    if (totalRots == 0) continue;

                    resetBoard();
                    String lastResp = "";
                    boolean flagFound = false;
                    for (int r = 0; r < 3 && !flagFound; r++) {
                        for (int c = 0; c < 3 && !flagFound; c++) {
                            String cell = (r+1) + "x" + (c+1);
                            for (int i = 0; i < rotCounts[r][c]; i++) {
                                lastResp = sendRotation(cell);
                                if (lastResp.contains("FLG")) {
                                    System.out.println("\n*** FLAG FOUND: " + lastResp + " ***");
                                    flagFound = true;
                                    break;
                                }
                            }
                        }
                    }
                    if (flagFound) return;
                }
            }
        }
        System.out.println("\nNo flag found. May need to try dC=1 as well.");
    }
}
