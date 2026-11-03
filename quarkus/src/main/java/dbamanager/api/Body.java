package dbamanager.api;

import dbamanager.core.ApiException;

import java.util.List;
import java.util.Map;

/** Lectura de cuerpos JSON genéricos. */
final class Body {
    private Body() {}

    static String str(Map<String, Object> b, String k) {
        Object v = b == null ? null : b.get(k);
        if (v == null) return null;
        String s = v.toString().strip();
        return s.isEmpty() ? null : s;
    }

    static String required(Map<String, Object> b, String k, String label) {
        String s = str(b, k);
        if (s == null) throw ApiException.of(422, label + " es obligatorio");
        return s;
    }

    static Long lng(Map<String, Object> b, String k) {
        Object v = b == null ? null : b.get(k);
        if (v == null || v.toString().isBlank()) return null;
        if (v instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(v.toString().strip());
        } catch (NumberFormatException e) {
            throw ApiException.of(422, k + " debe ser un número");
        }
    }

    static int integer(Map<String, Object> b, String k, int def) {
        Long v = lng(b, k);
        return v == null ? def : v.intValue();
    }

    static boolean bool(Map<String, Object> b, String k) {
        Object v = b == null ? null : b.get(k);
        return v instanceof Boolean x ? x : v != null && Boolean.parseBoolean(v.toString());
    }

    static boolean bool(Map<String, Object> b, String k, boolean def) {
        return b == null || !b.containsKey(k) || b.get(k) == null ? def : bool(b, k);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Map<String, Object> b, String k) {
        Object v = b == null ? null : b.get(k);
        return v instanceof Map ? (Map<String, Object>) v : null;
    }

    @SuppressWarnings("unchecked")
    static List<Object> list(Map<String, Object> b, String k) {
        Object v = b == null ? null : b.get(k);
        return v instanceof List ? (List<Object>) v : null;
    }
}
