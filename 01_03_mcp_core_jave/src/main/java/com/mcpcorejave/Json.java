package com.mcpcorejave;

import com.fasterxml.jackson.databind.ObjectMapper;

public final class Json {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Json() {
    }

    public static String json(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    public static String pretty(Object value) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    public static Object parse(String raw) {
        try {
            return MAPPER.readValue(raw, Object.class);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }
}
