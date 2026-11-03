package dbamanager.ops;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.exc.InvalidTypeIdException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import dbamanager.core.ApiException;
import dbamanager.ops.Ops.Op;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** JSON <-> operaciones, con errores por operación y campo. */
public final class OpParser {
    public static final ObjectMapper MAPPER = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);

    private OpParser() {}

    /** Valida y normaliza. Lanza 422 con todos los errores si alguna operación es inválida. */
    public static List<Op> parse(List<Map<String, Object>> raw, int limit) {
        if (raw == null || raw.isEmpty()) throw ApiException.of(422, "Agrega al menos una operación");
        if (raw.size() > limit) throw ApiException.of(422, "Una solicitud admite como máximo " + limit + " operaciones");
        List<Map<String, Object>> errors = new ArrayList<>();
        List<Op> parsed = new ArrayList<>();
        for (int i = 0; i < raw.size(); i++) {
            int seq = i + 1;
            V.Errs e = new V.Errs();
            Op op = convert(raw.get(i), e);
            if (op != null) op.validate(e);
            for (Map<String, Object> err : e.list) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("op_seq", seq);
                m.put("field", err.get("field").toString().isEmpty() ? "operación" : err.get("field"));
                m.put("message", err.get("message"));
                errors.add(m);
            }
            parsed.add(op);
        }
        if (!errors.isEmpty()) throw ApiException.invalid("Operaciones inválidas", errors);
        return parsed;
    }

    public static Op parseOne(Map<String, Object> raw) {
        return parse(List.of(raw), 1).get(0);
    }

    private static Op convert(Map<String, Object> raw, V.Errs e) {
        try {
            return MAPPER.convertValue(raw, Op.class);
        } catch (IllegalArgumentException ex) {
            Throwable c = ex.getCause();
            if (c instanceof InvalidTypeIdException) {
                e.add("type", "Tipo de operación desconocido: " + (raw == null ? null : raw.get("type")));
            } else if (c instanceof UnrecognizedPropertyException u) {
                e.add(path(u), "Campo no permitido");
            } else if (c instanceof JsonMappingException m) {
                e.add(path(m), "Valor no permitido");
            } else {
                e.add("", "Operación inválida");
            }
            return null;
        }
    }

    private static String path(JsonMappingException ex) {
        return ex.getPath().stream()
                .map(r -> r.getFieldName() != null ? r.getFieldName() : String.valueOf(r.getIndex()))
                .collect(Collectors.joining("."));
    }

    public static Map<String, Object> toMap(Op op) {
        return MAPPER.convertValue(op, new com.fasterxml.jackson.core.type.TypeReference<>() {});
    }

    public static String toJson(Op op) {
        try {
            return MAPPER.writeValueAsString(op);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
