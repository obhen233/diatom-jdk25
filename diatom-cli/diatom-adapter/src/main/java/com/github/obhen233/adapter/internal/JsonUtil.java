package com.github.obhen233.adapter.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.util.Map;

/**
 * Jackson utility wrapper for JSON serialization/deserialization.
 * Replaces the previous Gson-based implementation to align with diatom-core.
 */
public class JsonUtil {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private static final ObjectMapper MAPPER_NON_NULL = new ObjectMapper()
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL);

    /** Serialize to JSON, including null fields. */
    public static String toJson(Object obj) {
        try {
            return MAPPER.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize to JSON", e);
        }
    }

    /** Serialize to JSON, excluding null fields. */
    public static String toJsonNonNull(Object obj) {
        try {
            return MAPPER_NON_NULL.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize to JSON", e);
        }
    }

    /** Deserialize from JSON string. */
    public static <T> T fromJson(String json, Class<T> clazz) {
        try {
            return MAPPER.readValue(json, clazz);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to deserialize from JSON", e);
        }
    }

    /** Deserialize from JSON string with type reference (e.g., for generics). */
    public static <T> T fromJson(String json, com.fasterxml.jackson.core.type.TypeReference<T> typeRef) {
        try {
            return MAPPER.readValue(json, typeRef);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to deserialize from JSON", e);
        }
    }

    /** Deserialize a JSON object into a Map. */
    public static Map<String, Object> toMap(String json) {
        return fromJson(json, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
    }

    /** Deserialize a JSON object into a String map. */
    public static Map<String, String> toStringMap(String json) {
        return fromJson(json, new com.fasterxml.jackson.core.type.TypeReference<Map<String, String>>() {});
    }

    private JsonUtil() {}
}
