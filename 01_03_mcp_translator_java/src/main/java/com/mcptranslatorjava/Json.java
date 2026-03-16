package com.mcptranslatorjava;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public final class Json {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Json() {
    }

    public static String stringify(Object value) {
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

    public static JsonNode parseTree(String raw) {
        try {
            return MAPPER.readTree(raw == null || raw.isBlank() ? "{}" : raw);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    public static Object parseObject(String raw) {
        try {
            return MAPPER.readValue(raw, Object.class);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    public static <T> T convert(Object value, TypeReference<T> typeReference) {
        return MAPPER.convertValue(value, typeReference);
    }
}
