package dbamanager.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.util.List;
import java.util.Map;

/** JSON de la metadata (payloads, sentencias, detalles de bitácora). */
public final class Json {
    public static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    /** Orden estable de llaves: para firmas y sellos. */
    public static final ObjectMapper CANONICAL = MAPPER.copy()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
            .configure(com.fasterxml.jackson.databind.MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true);

    private Json() {}

    public static String write(Object o) {
        try {
            return MAPPER.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String canonical(Object o) {
        try {
            return CANONICAL.writeValueAsString(CANONICAL.readTree(MAPPER.writeValueAsString(o)));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    public static Map<String, Object> map(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return MAPPER.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    public static List<Map<String, Object>> list(String json) {
        try {
            return MAPPER.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> toMap(Object o) {
        return MAPPER.convertValue(o, Map.class);
    }
}
