package com.exercise03;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class SessionManager {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final ConcurrentHashMap<String, List<Map<String, Object>>> sessions = new ConcurrentHashMap<>();
    private final Path sessionsDir;
    private final boolean persistToDisk;

    public SessionManager(boolean persistToDisk) {
        this.persistToDisk = persistToDisk;
        this.sessionsDir = Path.of("sessions");
        if (persistToDisk) {
            try {
                Files.createDirectories(sessionsDir);
            } catch (IOException e) {
                throw new RuntimeException("Failed to create sessions directory", e);
            }
        }
    }

    public List<Map<String, Object>> getHistory(String sessionId) {
        if (persistToDisk) {
            loadFromDisk(sessionId);
        }
        return sessions.computeIfAbsent(sessionId, k -> new ArrayList<>());
    }

    public void addMessage(String sessionId, Map<String, Object> message) {
        List<Map<String, Object>> history = getHistory(sessionId);
        history.add(message);
        if (persistToDisk) {
            saveToDisk(sessionId);
        }
    }

    public void addMessages(String sessionId, List<Map<String, Object>> messages) {
        List<Map<String, Object>> history = getHistory(sessionId);
        history.addAll(messages);
        if (persistToDisk) {
            saveToDisk(sessionId);
        }
    }

    private void loadFromDisk(String sessionId) {
        if (sessions.containsKey(sessionId)) {
            return;
        }

        Path sessionFile = sessionsDir.resolve(sessionId + ".json");
        if (!Files.exists(sessionFile)) {
            return;
        }

        try {
            String json = Files.readString(sessionFile, StandardCharsets.UTF_8);
            List<Map<String, Object>> history = MAPPER.readValue(json, 
                MAPPER.getTypeFactory().constructCollectionType(List.class, Map.class));
            sessions.put(sessionId, history);
        } catch (IOException e) {
            System.err.println("Failed to load session " + sessionId + ": " + e.getMessage());
        }
    }

    private void saveToDisk(String sessionId) {
        List<Map<String, Object>> history = sessions.get(sessionId);
        if (history == null) {
            return;
        }

        Path sessionFile = sessionsDir.resolve(sessionId + ".json");
        try {
            String json = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(history);
            Files.writeString(sessionFile, json, StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("Failed to save session " + sessionId + ": " + e.getMessage());
        }
    }
}
