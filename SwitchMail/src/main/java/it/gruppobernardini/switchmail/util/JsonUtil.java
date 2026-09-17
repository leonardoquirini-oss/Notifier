package it.gruppobernardini.switchmail.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.util.List;
import java.util.Map;

/**
 * Jackson statico per i punti che non sono bean Spring (model, processor, DAO).
 * Il mapper delle API HTTP resta quello auto-configurato da Boot.
 */
public final class JsonUtil {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private JsonUtil() {
    }

    public static String write(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("serializzazione JSON fallita: " + e.getOriginalMessage(), e);
        }
    }

    public static String writePretty(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("serializzazione JSON fallita: " + e.getOriginalMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> readMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return MAPPER.readValue(json, Map.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("JSON non valido: " + e.getOriginalMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    public static List<String> readStringList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return MAPPER.readValue(json, List.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("JSON non valido: " + e.getOriginalMessage(), e);
        }
    }

    /** Valida senza interpretare: serve ai parametri di tipo JSON, che il processore legge da se'. */
    public static void requireValidJson(String json, String what) {
        try {
            MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(what + ": JSON non valido - " + e.getOriginalMessage(), e);
        }
    }
}
