package dbamanager.ops;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validación de operaciones: identificadores estrictos y expresiones libres que no pueden cerrar la
 * sentencia ni inyectar otras. Los errores se acumulan por campo.
 */
public final class V {
    public static final Pattern IDENT = Pattern.compile("^[A-Za-z][A-Za-z0-9_$#]{0,127}$");

    private V() {}

    /** Errores de una operación ({field, message}). */
    public static class Errs {
        public final List<Map<String, Object>> list = new ArrayList<>();

        public void add(String field, String message) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("field", field);
            m.put("message", message);
            list.add(m);
        }

        public boolean empty() { return list.isEmpty(); }
    }

    /** Errores anidados: escriben en el padre con el campo prefijado. */
    public static Errs nested(Errs parent, String path) {
        return new Nested(parent, path);
    }

    private static final class Nested extends Errs {
        private final Errs parent;
        private final String path;

        Nested(Errs parent, String path) {
            this.parent = parent;
            this.path = path;
        }

        @Override
        public void add(String field, String message) {
            parent.add(path + (field.isEmpty() ? "" : "." + field), message);
        }

        @Override
        public boolean empty() { return parent.empty(); }
    }

    public static String ident(Errs e, String field, String value) {
        String v = value == null ? "" : value.strip();
        if (!IDENT.matcher(v).matches()) {
            e.add(field, "Identificador inválido: '" + v + "' (letras, números, _ $ #; inicia con letra)");
            return value;
        }
        return v.toUpperCase();
    }

    public static String optIdent(Errs e, String field, String value) {
        return value == null || value.isBlank() ? null : ident(e, field, value);
    }

    public static List<String> idents(Errs e, String field, List<String> values) {
        if (values == null) return new ArrayList<>();
        List<String> out = new ArrayList<>();
        for (int i = 0; i < values.size(); i++) out.add(ident(e, field + "." + i, values.get(i)));
        return out;
    }

    /** ESQUEMA.NOMBRE o NOMBRE (p. ej. un indextype). */
    public static String qualified(Errs e, String field, String value) {
        List<String> parts = new ArrayList<>();
        for (String p : (value == null ? "" : value).split("\\.", -1)) parts.add(ident(e, field, p));
        return String.join(".", parts);
    }

    public static String safeExpr(Errs e, String field, String value, String what) {
        return safeExpr(e, field, value, what, false);
    }

    /**
     * Expresión SQL libre: sin ';' ni comentarios fuera de literales, con comillas y paréntesis
     * balanceados. Salvo allowList, sin comas fuera de paréntesis (no puede "cerrarse" y agregar
     * otra columna o cláusula).
     */
    public static String safeExpr(Errs e, String field, String value, String what, boolean allowList) {
        if (value == null || value.isBlank()) return null;
        String v = value.strip();
        String outside = v.replaceAll("'(?:[^']|'')*'", "''").replaceAll("\"[^\"]*\"", "\"\"");
        if (count(outside, '\'') % 2 != 0 || count(outside, '"') % 2 != 0) {
            e.add(field, what + " tiene comillas sin cerrar");
            return v;
        }
        if (outside.contains(";") || outside.contains("--") || outside.contains("/*")) {
            e.add(field, what + " no puede contener ';' ni comentarios fuera de comillas");
            return v;
        }
        int depth = 0;
        for (char c : outside.toCharArray()) {
            if (c == '(') depth++;
            if (c == ')') depth--;
            if (depth < 0) break;
            if (c == ',' && depth == 0 && !allowList) {
                e.add(field, what + " no puede tener comas fuera de paréntesis");
                return v;
            }
        }
        if (depth != 0) {
            e.add(field, what + " tiene paréntesis desbalanceados");
            return v;
        }
        if (v.length() > 4000) e.add(field, what + " es demasiado larga (máximo 4000 caracteres)");
        return v;
    }

    private static int count(String s, char c) {
        int n = 0;
        for (char x : s.toCharArray()) if (x == c) n++;
        return n;
    }

    public static void oneOf(Errs e, String field, String value, Set<String> allowed) {
        if (value != null && !allowed.contains(value)) {
            e.add(field, "Valor no permitido; opciones: " + String.join(", ", allowed.stream().sorted().toList()));
        }
    }

    public static void required(Errs e, String field, Object value) {
        if (value == null || (value instanceof String s && s.isBlank())) e.add(field, "Campo obligatorio");
    }

    public static void maxLen(Errs e, String field, String value, int max) {
        if (value != null && value.length() > max) e.add(field, "Debe tener máximo " + max + " caracteres");
    }

    public static void range(Errs e, String field, Integer value, int min, int max) {
        if (value != null && (value < min || value > max)) e.add(field, "Debe estar entre " + min + " y " + max);
    }
}
