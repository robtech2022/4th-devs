package com.mcptranslatorjava;

import com.fasterxml.jackson.core.type.TypeReference;
import com.mcptranslatorjava.files.FilesMcpClient;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class TranslationLoop {
    private static final int MAX_TRANSLATIONS = 3;

    private final Config config;
    private final Agent agent;
    private final Stats stats;
    private final Set<String> inProgress = new HashSet<>();
    private final Set<String> loggedSkipped = new HashSet<>();
    private int completedCount;
    private ScheduledExecutorService executor;

    public TranslationLoop(Config config, Agent agent, Stats stats) {
        this.config = config;
        this.agent = agent;
        this.stats = stats;
    }

    public void start(FilesMcpClient mcpClient, List<Map<String, Object>> mcpTools) {
        Log.start("Watching " + config.sourceDir() + " (every " + config.pollIntervalMs() + "ms)");
        Log.info("Output: " + config.targetDir());
        ensureDirectories(mcpClient);
        tick(mcpClient, mcpTools);
        executor = Executors.newSingleThreadScheduledExecutor();
        executor.scheduleWithFixedDelay(() -> tick(mcpClient, mcpTools), config.pollIntervalMs(), config.pollIntervalMs(), TimeUnit.MILLISECONDS);
    }

    public void stop() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    private void tick(FilesMcpClient mcpClient, List<Map<String, Object>> mcpTools) {
        try {
            List<String> sourceFiles = listFiles(mcpClient, config.sourceDir(), true);
            List<String> translatedFiles = listFiles(mcpClient, config.targetDir(), false);
            for (String filename : sourceFiles) {
                if (translatedFiles.contains(filename)) {
                    continue;
                }
                if (completedCount >= MAX_TRANSLATIONS) {
                    Log.warn("Reached translation limit (" + MAX_TRANSLATIONS + "). Restart the script to continue.");
                    return;
                }
                translateFile(filename, mcpClient, mcpTools);
            }
        } catch (Exception ex) {
            Log.error("Watch loop error", ex.getMessage());
        }
    }

    private List<String> listFiles(FilesMcpClient mcpClient, String dir, boolean filterByExtension) {
        try {
            Object result = mcpClient.callTool("fs_read", Map.of("path", dir, "mode", "list"));
            Map<String, Object> map = Json.convert(result, new TypeReference<>() {});
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> entries = (List<Map<String, Object>>) map.getOrDefault("entries", List.of());
            return entries.stream()
                    .filter(entry -> "file".equals(entry.get("kind")) || "file".equals(entry.get("type")))
                    .map(entry -> String.valueOf(entry.getOrDefault("name", String.valueOf(entry.get("path")))))
                    .filter(name -> !filterByExtension || config.supportedExtensions().stream().anyMatch(name::endsWith))
                    .toList();
        } catch (Exception ex) {
            return List.of();
        }
    }

    private void translateFile(String filename, FilesMcpClient mcpClient, List<Map<String, Object>> mcpTools) {
        synchronized (this) {
            if (inProgress.contains(filename)) {
                if (!loggedSkipped.contains(filename)) {
                    Log.debug(filename + " - translation in progress, waiting...");
                    loggedSkipped.add(filename);
                }
                return;
            }
            loggedSkipped.remove(filename);
            inProgress.add(filename);
        }

        String sourcePath = config.sourceDir() + "/" + filename;
        String targetPath = config.targetDir() + "/" + filename;
        Log.info("Translating: " + filename);
        String prompt = "Translate \"" + sourcePath + "\" to English and save to \"" + targetPath + "\".";

        try {
            agent.run(prompt, mcpClient, mcpTools);
            synchronized (this) {
                completedCount++;
            }
            Log.success("Translated: " + filename + " (" + completedCount + "/" + MAX_TRANSLATIONS + ")");
            Log.info("Stats: " + stats.summary());
        } catch (Exception ex) {
            Log.error("Translation failed: " + filename, ex.getMessage());
        } finally {
            synchronized (this) {
                inProgress.remove(filename);
            }
        }
    }

    private void ensureDirectories(FilesMcpClient mcpClient) {
        try {
            mcpClient.callTool("fs_manage", Map.of("operation", "mkdir", "path", config.sourceDir(), "recursive", true));
        } catch (Exception ignored) {
        }
        try {
            mcpClient.callTool("fs_manage", Map.of("operation", "mkdir", "path", config.targetDir(), "recursive", true));
        } catch (Exception ignored) {
        }
    }
}
