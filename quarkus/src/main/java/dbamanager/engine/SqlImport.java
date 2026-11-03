package dbamanager.engine;

import dbamanager.ops.Ops;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Convierte un script SQL de Oracle en operaciones visuales. Lo que se reconoce se transforma en
 * operaciones estructuradas; lo que no puede representarse sin cambiar su significado se conserva
 * como CUSTOM_SQL para que nada del script se pierda.
 */
public final class SqlImport {
    static final String NAME = "(?:\"[^\"]+\"|[A-Za-z][\\w$#]*)";
    static final String QNAME = "(" + NAME + ")(?:\\s*\\.\\s*(" + NAME + "))?";
    public static final Pattern PLSQL_START = Pattern.compile(
            "^\\s*(DECLARE|BEGIN|CREATE\\s+(OR\\s+REPLACE\\s+)?(EDITIONABLE\\s+|NONEDITIONABLE\\s+)?(PROCEDURE|FUNCTION|PACKAGE|TRIGGER|TYPE)\\b)",
            Pattern.CASE_INSENSITIVE);
    private static final Set<String> TYPES = OracleDdl.DATA_TYPES.stream().map(t -> (String) t.get("name")).collect(Collectors.toSet());
    private static final Map<String, String> TYPE_ALIASES = Map.of("VARCHAR", "VARCHAR2", "INT", "INTEGER",
            "SMALLINT", "INTEGER", "DECIMAL", "NUMBER", "NUMERIC", "NUMBER");
    /** Atributos físicos que se ignoran (la base usa sus valores por defecto). */
    static final Pattern PHYSICAL = Pattern.compile(
            "\\b(PCTFREE\\s+\\d+|PCTUSED\\s+\\d+|INITRANS\\s+\\d+|MAXTRANS\\s+\\d+|STORAGE\\s*\\([^)]*\\)|NOCACHE|CACHE"
                    + "|NOPARALLEL|PARALLEL(\\s+\\d+)?|NOMONITORING|MONITORING|SEGMENT\\s+CREATION\\s+(IMMEDIATE|DEFERRED)"
                    + "|ENABLE\\s+ROW\\s+MOVEMENT|DISABLE\\s+ROW\\s+MOVEMENT|NOSORT|SORT|COMPUTE\\s+STATISTICS"
                    + "|NOROWDEPENDENCIES|ROWDEPENDENCIES|NOFLASHBACK\\s+ARCHIVE)\\b", Pattern.CASE_INSENSITIVE);
    private static final int CI = Pattern.CASE_INSENSITIVE;
    private static final int CIS = Pattern.CASE_INSENSITIVE | Pattern.DOTALL;

    private SqlImport() {}

    /** Resultado de una sentencia. */
    public static final class Imported {
        public Map<String, Object> op;
        public List<String> notes = new ArrayList<>();
        public String source = "";
        public List<String> absorbed = new ArrayList<>();
        public boolean companion;

        Imported(Map<String, Object> op) { this.op = op; }

        Imported(Map<String, Object> op, List<String> notes) {
            this.op = op;
            if (notes != null) this.notes.addAll(notes);
        }
    }

    /** Busca la definición actual de una columna (para MODIFY). */
    public interface ColumnLookup {
        Map<String, Object> find(String schema, String table, String column);
    }

    static class ParseError extends RuntimeException {
        ParseError(String m) { super(m); }
    }

    // ------------------------------------------------------------------ utilidades léxicas
    private static Matcher at(String regex, String text, int flags) {
        Matcher m = Pattern.compile(regex, flags).matcher(text);
        return m.lookingAt() ? m : null;
    }

    private static Matcher full(String regex, String text, int flags) {
        Matcher m = Pattern.compile(regex, flags).matcher(text);
        return m.matches() ? m : null;
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    /** Quita comentarios -- y /* *\/ respetando literales entre comillas. */
    public static String stripComments(String text) {
        StringBuilder out = new StringBuilder();
        int i = 0, n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (c == '\'' || c == '"') {
                int j = i + 1;
                while (j < n) {
                    if (text.charAt(j) == c) {
                        if (c == '\'' && j + 1 < n && text.charAt(j + 1) == '\'') {
                            j += 2;
                            continue;
                        }
                        break;
                    }
                    j++;
                }
                out.append(text, i, Math.min(j + 1, n));
                i = j + 1;
            } else if (text.startsWith("--", i)) {
                int j = text.indexOf('\n', i);
                i = j < 0 ? n : j;
            } else if (text.startsWith("/*", i)) {
                int j = text.indexOf("*/", i + 2);
                out.append(' ');
                i = j < 0 ? n : j + 2;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString().strip();
    }

    /** Divide en sentencias: ';' para SQL y una línea '/' para bloques PL/SQL. */
    public static List<String> splitScript(String sql) {
        List<String> stmts = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        boolean inQ = false, inDq = false, inBlock = false;
        for (String line : sql.split("(?<=\n)")) {
            if (!(inQ || inDq || inBlock) && line.strip().equals("/")) {
                emit(buf, stmts);
                continue;
            }
            int i = 0, n = line.length();
            while (i < n) {
                char c = line.charAt(i);
                if (inBlock) {
                    if (line.startsWith("*/", i)) {
                        inBlock = false;
                        buf.append("*/");
                        i += 2;
                        continue;
                    }
                } else if (inQ) {
                    inQ = c != '\'';
                } else if (inDq) {
                    inDq = c != '"';
                } else if (line.startsWith("--", i)) {
                    buf.append(line.substring(i));
                    break;
                } else if (line.startsWith("/*", i)) {
                    inBlock = true;
                    buf.append("/*");
                    i += 2;
                    continue;
                } else if (c == '\'') {
                    inQ = true;
                } else if (c == '"') {
                    inDq = true;
                } else if (c == ';' && !PLSQL_START.matcher(stripComments(buf.toString())).find()) {
                    emit(buf, stmts);
                    i++;
                    continue;
                }
                buf.append(c);
                i++;
            }
        }
        emit(buf, stmts);
        return stmts;
    }

    private static void emit(StringBuilder buf, List<String> stmts) {
        String text = buf.toString().strip();
        buf.setLength(0);
        if (!stripComments(text).isEmpty()) stmts.add(text);
    }

    /** Divide por `sep` a nivel 0 de paréntesis y fuera de comillas. */
    static List<String> splitTop(String text, char sep) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        Character q = null;
        StringBuilder cur = new StringBuilder();
        for (char c : text.toCharArray()) {
            if (q != null) {
                if (c == q) q = null;
            } else if (c == '\'' || c == '"') {
                q = c;
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (c == sep && depth == 0) {
                parts.add(cur.toString().strip());
                cur.setLength(0);
                continue;
            }
            cur.append(c);
        }
        if (!cur.toString().isBlank()) parts.add(cur.toString().strip());
        return parts;
    }

    static List<String> splitTop(String text) {
        return splitTop(text, ',');
    }

    static int matchingParen(String text, int start) {
        int depth = 0;
        Character q = null;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (q != null) {
                if (c == q) q = null;
            } else if (c == '\'' || c == '"') {
                q = c;
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) return i;
            }
        }
        throw new ParseError("Paréntesis sin cerrar");
    }

    /** "(a, b) resto" -> ["a, b", "resto"]. */
    static String[] parenBody(String text) {
        text = text.stripLeading();
        if (!text.startsWith("(")) throw new ParseError("Se esperaba '(' en: " + head(text, 40));
        int end = matchingParen(text, 0);
        return new String[]{text.substring(1, end), text.substring(end + 1)};
    }

    static String unq(String name) {
        if (name == null) return null;
        if (name.startsWith("\"")) {
            String inner = name.substring(1, name.length() - 1);
            if (!inner.equals(inner.toUpperCase()) || !inner.matches("^[A-Z][A-Z0-9_$#]*$"))
                // Convertirlo cambiaría el nombre real del objeto: se conserva el SQL tal cual.
                throw new ParseError("el nombre \"" + inner + "\" distingue mayúsculas/minúsculas o tiene caracteres especiales");
            return inner;
        }
        return name.toUpperCase();
    }

    static String[] qname(String a, String b, String ds) {
        return b != null ? new String[]{unq(a), unq(b)} : new String[]{ds, unq(a)};
    }

    static List<String> names(String text) {
        return splitTop(text).stream().map(c -> unq(c.strip())).collect(Collectors.toCollection(ArrayList::new));
    }

    static String unescape(String lit) {
        return lit.replace("''", "'");
    }

    private static boolean wordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '#';
    }

    /** Como re.search pero solo a nivel superior (fuera de paréntesis y comillas). */
    static Matcher searchTop(String regex, String text) {
        Matcher m = Pattern.compile(regex, CIS).matcher(text);
        int depth = 0;
        Character q = null;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (q != null) {
                if (c == q) q = null;
            } else if (c == '\'' || c == '"') {
                q = c;
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (depth == 0 && (i == 0 || !wordChar(text.charAt(i - 1)))) {
                m.region(i, text.length());
                if (m.lookingAt()) return m;
            }
        }
        return null;
    }

    /** Busca a nivel superior y lo quita. [0] = Matcher o null, [1] = texto restante. */
    static Object[] take(String regex, String text) {
        Matcher m = searchTop(regex, text);
        if (m == null) return new Object[]{null, text};
        return new Object[]{m, (text.substring(0, m.start()) + " " + text.substring(m.end())).strip()};
    }

    static String head(String s, int n) {
        return s.length() > n ? s.substring(0, n) : s;
    }

    static String physicalLeft(String s) {
        return PHYSICAL.matcher(s).replaceAll("").strip();
    }

    // ------------------------------------------------------------------ tipos y columnas
    static final Pattern CLAUSE_KW = Pattern.compile(
            "(GENERATED\\s+(?:ALWAYS|BY\\s+DEFAULT(?:\\s+ON\\s+NULL)?)\\s+AS\\s+IDENTITY\\b|(?:GENERATED\\s+ALWAYS\\s+)?AS\\s*\\("
                    + "|ON\\s+DELETE\\s+(?:CASCADE|SET\\s+NULL)\\b|DEFAULT\\s+ON\\s+NULL\\b|DEFAULT\\b|NOT\\s+NULL\\b|NULL\\b|CONSTRAINT\\b|PRIMARY\\s+KEY\\b|UNIQUE\\b|CHECK\\b|REFERENCES\\b"
                    + "|ENABLE\\b|DISABLE\\b|VISIBLE\\b|INVISIBLE\\b|VIRTUAL\\b|USING\\s+INDEX\\b"
                    + "|NOT\\s+DEFERRABLE\\b|DEFERRABLE\\b|INITIALLY\\b|VALIDATE\\b|NOVALIDATE\\b|RELY\\b|NORELY\\b)",
            CI);

    static String normKw(String raw) {
        String kw = raw.toUpperCase().replaceAll("\\s+", " ").strip();
        if (kw.startsWith("GENERATED") && kw.endsWith("IDENTITY")) return "IDENTITY";
        if (kw.endsWith("(")) return "AS (";
        if (kw.startsWith("DEFAULT")) return "DEFAULT";
        if (kw.startsWith("ON DELETE")) return "ON DELETE";
        return kw;
    }

    static final Set<String> STATE_KW = Set.of("DEFERRABLE", "NOT DEFERRABLE", "INITIALLY", "ENABLE", "DISABLE", "VALIDATE",
            "NOVALIDATE", "USING INDEX", "RELY", "NORELY");

    /** Separa "... DEFAULT x NOT NULL CONSTRAINT c CHECK (...)" en [palabra clave, texto]. */
    static List<String[]> splitClauses(String rest) {
        List<Integer> marks = new ArrayList<>();
        int depth = 0;
        Character q = null;
        int i = 0;
        Matcher m = CLAUSE_KW.matcher(rest);
        while (i < rest.length()) {
            char c = rest.charAt(i);
            if (q != null) {
                if (c == q) q = null;
            } else if (c == '\'' || c == '"') {
                q = c;
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (depth == 0 && (i == 0 || !wordChar(rest.charAt(i - 1)))) {
                m.region(i, rest.length());
                if (m.lookingAt()) {
                    marks.add(i);
                    i = m.end();
                    if (m.group(1).endsWith("(")) depth++;  // el paréntesis ya consumido por la palabra clave
                    continue;
                }
            }
            i++;
        }
        if ((!marks.isEmpty() && !rest.substring(0, marks.get(0)).isBlank()) || (marks.isEmpty() && !rest.isBlank()))
            throw new ParseError("No se reconoce: " + head(rest.substring(0, marks.isEmpty() ? rest.length() : marks.get(0)).strip(), 60));
        List<String[]> out = new ArrayList<>();
        for (int k = 0; k < marks.size(); k++) {
            String chunk = rest.substring(marks.get(k), k + 1 < marks.size() ? marks.get(k + 1) : rest.length()).strip();
            Matcher km = CLAUSE_KW.matcher(chunk);
            km.lookingAt();
            String kw = normKw(km.group(1));
            if (!out.isEmpty() && out.get(out.size() - 1)[0].equals("DEFAULT") && kw.equals("NULL")
                    && out.get(out.size() - 1)[1].matches("(?is)DEFAULT(\\s+ON\\s+NULL)?")) {
                out.get(out.size() - 1)[1] = out.get(out.size() - 1)[1] + " " + chunk;  // DEFAULT NULL
                continue;
            }
            out.add(new String[]{kw, chunk});
        }
        return out;
    }

    static String defaultExpr(String chunk) {
        String expr = chunk.replaceFirst("(?is)^DEFAULT\\s+(ON\\s+NULL\\s+)?", "").strip();
        return expr.isEmpty() || expr.equalsIgnoreCase("NULL") ? null : expr;
    }

    /** [campos de tipo, resto del texto]. */
    static Object[] parseType(String text) {
        Matcher m = at("\\s*TIMESTAMP\\s*(?:\\(\\s*(\\d+)\\s*\\))?(\\s+WITH\\s+(LOCAL\\s+)?TIME\\s+ZONE)?", text, CI);
        if (m != null) {
            String name = "TIMESTAMP" + (m.group(3) != null ? " WITH LOCAL TIME ZONE" : m.group(2) != null ? " WITH TIME ZONE" : "");
            Map<String, Object> out = map("data_type", name, "precision", m.group(1) != null ? Integer.parseInt(m.group(1)) : null);
            return new Object[]{out, text.substring(m.end())};
        }
        m = at("\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*(\\(([^)]*)\\))?", text, 0);
        if (m == null) throw new ParseError("No se reconoce el tipo de dato");
        String name = m.group(1).toUpperCase();
        name = TYPE_ALIASES.getOrDefault(name, name);
        if (!TYPES.contains(name)) throw new ParseError("tipo de dato " + name + " no soportado visualmente");
        List<String> args = new ArrayList<>();
        if (m.group(3) != null) for (String a : m.group(3).split(",")) if (!a.isBlank()) args.add(a.strip().toUpperCase());
        Map<String, Object> out = map("data_type", name);
        if (!args.isEmpty()) {
            if (Set.of("VARCHAR2", "NVARCHAR2", "CHAR", "NCHAR", "RAW").contains(name)) {
                String[] size = args.get(0).split("\\s+");
                out.put("length", Integer.parseInt(size[0]));
                if (size.length > 1 && size[1].equals("CHAR") && (name.equals("VARCHAR2") || name.equals("CHAR")))
                    out.put("char_semantics", true);
            } else if (name.equals("NUMBER")) {
                if (!args.get(0).equals("*")) out.put("precision", Integer.parseInt(args.get(0)));
                if (args.size() > 1) out.put("scale", Integer.parseInt(args.get(1)));
            } else if (name.equals("FLOAT")) {
                out.put("precision", Integer.parseInt(args.get(0)));
            }
        }
        return new Object[]{out, text.substring(m.end())};
    }

    static void parseConstraintState(List<String[]> chunks, Map<String, Object> c, List<String> notes) {
        for (String[] kc : chunks) {
            String kw = kc[0], chunk = kc[1];
            String up = chunk.toUpperCase().replaceAll("\\s+", " ");
            switch (kw) {
                case "DEFERRABLE" -> c.put("deferrable", true);
                case "INITIALLY" -> c.put("initially_deferred", up.contains("DEFERRED"));
                case "DISABLE" -> c.put("enabled", false);
                case "ENABLE" -> c.put("enabled", true);
                case "NOVALIDATE" -> c.put("validated", false);
                case "USING INDEX" -> {
                    Matcher ts = Pattern.compile("TABLESPACE\\s+(" + NAME + ")", CI).matcher(chunk);
                    if (ts.find()) c.put("index_tablespace", unq(ts.group(1)));
                    String rest = chunk.replaceAll("(?i)USING\\s+INDEX|TABLESPACE\\s+" + NAME, "");
                    if (!rest.isBlank()) {
                        if (!physicalLeft(rest).isEmpty()) throw new ParseError("opciones USING INDEX no soportadas: " + head(rest.strip(), 60));
                        notes.add("Se ignoraron opciones físicas del índice de la restricción");
                    }
                }
                case "VALIDATE", "NOT DEFERRABLE", "RELY", "NORELY" -> { }
                default -> throw new ParseError("cláusula " + kw + " no soportada en la restricción");
            }
        }
    }

    /** "[CONSTRAINT x] PRIMARY KEY (..)|UNIQUE (..)|FOREIGN KEY (..) REFERENCES t (..) ...|CHECK (..) [estado]". */
    static Map<String, Object> parseTableConstraint(String text, List<String> notes) {
        Map<String, Object> c = new LinkedHashMap<>();
        Matcher m = full("^\\s*CONSTRAINT\\s+(" + NAME + ")\\s+(.*)$", text, CIS);
        if (m != null) {
            c.put("name", unq(m.group(1)));
            text = m.group(2);
        }
        text = text.strip();
        m = at("^(PRIMARY\\s+KEY|UNIQUE|FOREIGN\\s+KEY|CHECK)\\s*", text, CI);
        if (m == null) throw new ParseError("restricción no reconocida: " + head(text, 50));
        String kind = m.group(1).toUpperCase().replaceAll("\\s+", "_");
        String[] br = parenBody(text.substring(m.end()));
        String body = br[0], rest = br[1];
        c.put("kind", kind);
        if (kind.equals("CHECK")) c.put("condition", body.strip());
        else c.put("columns", names(body));
        if (kind.equals("FOREIGN_KEY")) {
            Matcher r = at("^\\s*REFERENCES\\s+" + QNAME + "\\s*", rest, CI);
            if (r == null) throw new ParseError("FOREIGN KEY sin REFERENCES");
            if (r.group(2) != null) {
                c.put("ref_schema", unq(r.group(1)));
                c.put("ref_table", unq(r.group(2)));
            } else {
                c.put("ref_schema", null);
                c.put("ref_table", unq(r.group(1)));
            }
            rest = rest.substring(r.end());
            if (rest.stripLeading().startsWith("(")) {
                String[] rb = parenBody(rest);
                c.put("ref_columns", names(rb[0]));
                rest = rb[1];
            }
            Matcher od = at("^\\s*ON\\s+DELETE\\s+(CASCADE|SET\\s+NULL)", rest, CI);
            if (od != null) {
                c.put("on_delete", od.group(1).equalsIgnoreCase("CASCADE") ? "CASCADE" : "SET_NULL");
                rest = rest.substring(od.end());
            }
        }
        parseConstraintState(rest.isBlank() ? List.of() : splitClauses(rest), c, notes);
        return c;
    }

    /** [columna, restricciones inline]. */
    @SuppressWarnings("unchecked")
    static Object[] parseColumn(String text, List<String> notes) {
        Matcher m = full("\\s*(" + NAME + ")\\s+(.*)$", text, Pattern.DOTALL);
        if (m == null) throw new ParseError("No se reconoce la columna: " + head(text, 60));
        String colName = unq(m.group(1));
        Map<String, Object> col;
        String rest;
        if (at("^\\s*(GENERATED\\s+ALWAYS\\s+)?AS\\s*\\(", m.group(2), CI) != null) {
            col = map("data_type", null);  // columna virtual sin tipo explícito
            rest = m.group(2);
        } else {
            Object[] t = parseType(m.group(2));
            col = (Map<String, Object>) t[0];
            rest = (String) t[1];
        }
        col.put("name", colName);
        col.put("nullable", true);
        List<Map<String, Object>> constraints = new ArrayList<>();
        Map<String, Object> cur = null;
        List<String[]> clauses = rest.isBlank() ? List.of() : splitClauses(rest);
        int k = 0;
        while (k < clauses.size()) {
            String kw = clauses.get(k)[0];
            String chunk = clauses.get(k)[1];
            k++;
            switch (kw) {
                case "DEFAULT" -> {
                    col.put("default_on_null", at("^DEFAULT\\s+ON\\s+NULL\\b", chunk, CI) != null);
                    col.put("default", defaultExpr(chunk));
                }
                case "IDENTITY" -> {
                    String up = chunk.toUpperCase().replaceAll("\\s+", " ");
                    col.put("identity", true);
                    col.put("nullable", false);
                    col.put("identity_mode", up.startsWith("GENERATED ALWAYS") ? "ALWAYS" : up.contains("ON NULL") ? "BY_DEFAULT_ON_NULL" : "BY_DEFAULT");
                    Matcher st = Pattern.compile("START\\s+WITH\\s+(-?\\d+)", CI).matcher(chunk);
                    Matcher inc = Pattern.compile("INCREMENT\\s+BY\\s+(-?\\d+)", CI).matcher(chunk);
                    col.put("identity_start", st.find() ? Long.parseLong(st.group(1)) : null);
                    col.put("identity_increment", inc.find() ? Long.parseLong(inc.group(1)) : null);
                    String opts = chunk.replaceAll("(?is)^GENERATED.*?IDENTITY|START\\s+WITH\\s+-?\\d+|INCREMENT\\s+BY\\s+-?\\d+|[()\\s]", "");
                    if (!opts.isEmpty()) notes.add(colName + ": opciones de identidad (CACHE, MAXVALUE…) ignoradas");
                }
                case "AS (" -> {
                    String[] pb = parenBody(chunk.substring(chunk.indexOf('(')));
                    col.put("virtual_expression", pb[0].strip());
                    if (!pb[1].isBlank() && !pb[1].strip().equalsIgnoreCase("VIRTUAL"))
                        throw new ParseError(colName + ": cláusula no reconocida tras la expresión virtual");
                }
                case "VIRTUAL", "ENABLE", "VALIDATE" -> { }
                case "INVISIBLE" -> col.put("invisible", true);
                case "VISIBLE" -> col.put("invisible", false);
                case "NOT NULL" -> col.put("nullable", false);
                case "NULL" -> col.put("nullable", true);
                case "CONSTRAINT" -> cur = map("name", unq(chunk.split("\\s+")[1]));
                case "PRIMARY KEY", "UNIQUE", "CHECK", "REFERENCES" -> {
                    Map<String, Object> c = cur != null ? cur : new LinkedHashMap<>();
                    cur = null;
                    if (kw.equals("PRIMARY KEY")) {
                        c.put("kind", "PRIMARY_KEY");
                        c.put("columns", new ArrayList<>(List.of(colName)));
                        col.put("nullable", false);
                    } else if (kw.equals("UNIQUE")) {
                        c.put("kind", "UNIQUE");
                        c.put("columns", new ArrayList<>(List.of(colName)));
                    } else if (kw.equals("CHECK")) {
                        c.put("kind", "CHECK");
                        c.put("condition", parenBody(chunk.substring(5))[0].strip());
                    } else {
                        if (k < clauses.size() && clauses.get(k)[0].equals("ON DELETE")) {
                            chunk += " " + clauses.get(k)[1];
                            k++;
                        }
                        Map<String, Object> fk = parseTableConstraint("FOREIGN KEY (" + colName + ") " + chunk, notes);
                        fk.forEach((k2, v) -> { if (!k2.equals("name")) c.put(k2, v); });
                    }
                    List<String[]> states = new ArrayList<>();  // estados que siguen a la restricción (DEFERRABLE, DISABLE, ...)
                    while (k < clauses.size() && STATE_KW.contains(clauses.get(k)[0])) states.add(clauses.get(k++));
                    parseConstraintState(states, c, notes);
                    constraints.add(c);
                }
                default -> throw new ParseError(colName + ": cláusula " + kw + " no soportada visualmente");
            }
        }
        return new Object[]{col, constraints};
    }

    // ------------------------------------------------------------------ opciones de tabla y particiones
    static Object[] parsePartitioning(String text) {
        Matcher m = at("^\\s*PARTITION\\s+BY\\s+(RANGE|LIST|HASH)\\s*", text, CI);
        if (m == null) throw new ParseError("tipo de particionado no soportado visualmente");
        String method = m.group(1).toUpperCase();
        String[] cb = parenBody(text.substring(m.end()));
        String rest = cb[1];
        List<Map<String, Object>> parts = new ArrayList<>();
        Map<String, Object> pt = map("method", method, "columns", names(cb[0]), "partitions", parts);
        Matcher im = at("^\\s*INTERVAL\\s*", rest, CI);
        if (im != null) {
            String[] iv = parenBody(rest.substring(im.end()));
            pt.put("interval", iv[0].strip());
            rest = iv[1];
        }
        if (at("^\\s*SUBPARTITION\\b", rest, CI) != null) throw new ParseError("subparticiones no soportadas visualmente");
        Matcher hm = at("^\\s*PARTITIONS\\s+(\\d+)", rest, CI);
        if (hm != null) {
            pt.put("hash_count", Integer.parseInt(hm.group(1)));
            rest = rest.substring(hm.end());
            Matcher sm = at("^\\s*STORE\\s+IN\\s*\\([^)]*\\)", rest, CI);
            if (sm != null) rest = rest.substring(sm.end());
            return new Object[]{pt, rest};
        }
        String[] bb = parenBody(rest);
        rest = bb[1];
        for (String p : splitTop(bb[0])) {
            Matcher pm = full("^\\s*PARTITION\\s+(" + NAME + ")\\s*(.*)$", p, CIS);
            if (pm == null) throw new ParseError("partición no reconocida: " + head(p, 50));
            Map<String, Object> part = map("name", unq(pm.group(1)));
            String prest = pm.group(2);
            Matcher vm = at("^\\s*VALUES\\s+(LESS\\s+THAN\\s*)?", prest, CI);
            if (vm != null) {
                String[] vb = parenBody(prest.substring(vm.end()));
                part.put("values", vb[0].strip());
                prest = vb[1];
            }
            Object[] tk = take("TABLESPACE\\s+(" + NAME + ")", prest);
            if (tk[0] != null) part.put("tablespace", unq(((Matcher) tk[0]).group(1)));
            prest = physicalLeft(((String) tk[1]).replaceAll("(?i)\\b(COMPRESS|NOCOMPRESS|LOGGING|NOLOGGING)\\b", ""));
            if (!prest.isEmpty()) throw new ParseError("opciones de la partición " + part.get("name") + " no soportadas: " + head(prest, 50));
            parts.add(part);
        }
        return new Object[]{pt, rest};
    }

    static void parseTableOptions(String tail, Map<String, Object> op, List<String> notes) {
        String rest = tail;
        Object[] t = take("ON\\s+COMMIT\\s+(DELETE|PRESERVE)\\s+ROWS", rest);
        rest = (String) t[1];
        if (t[0] != null) op.put("temporary", ((Matcher) t[0]).group(1).toUpperCase() + "_ROWS");
        Matcher pm = searchTop("PARTITION\\s+BY\\b", rest);
        if (pm != null) {
            int start = pm.start();
            Object[] pp = parsePartitioning(rest.substring(start));
            op.put("partitioning", pp[0]);
            rest = (rest.substring(0, start) + " " + pp[1]).strip();
        }
        t = take("TABLESPACE\\s+(" + NAME + ")", rest);
        rest = (String) t[1];
        if (t[0] != null) op.put("tablespace", unq(((Matcher) t[0]).group(1)));
        t = take("NOCOMPRESS\\b", rest);
        rest = (String) t[1];
        if (t[0] != null) op.put("compression", "NONE");
        t = take("(ROW\\s+STORE\\s+)?COMPRESS(\\s+(BASIC|ADVANCED|FOR\\s+OLTP))?\\b", rest);
        rest = (String) t[1];
        if (t[0] != null) {
            Matcher cm = (Matcher) t[0];
            String level = (cm.group(3) != null ? cm.group(3) : "BASIC").toUpperCase().replaceAll("\\s+", " ");
            op.put("compression", level.equals("ADVANCED") || level.equals("FOR OLTP") ? "ADVANCED" : "BASIC");
        }
        t = take("(NO)?LOGGING\\b", rest);
        rest = (String) t[1];
        if (t[0] != null) op.put("logging", ((Matcher) t[0]).group(1) == null);
        if (!physicalLeft(rest).isEmpty()) throw new ParseError("opciones de tabla no soportadas visualmente: " + head(physicalLeft(rest), 80));
        if (!rest.isBlank()) notes.add("Se ignoraron opciones físicas (PCTFREE, STORAGE, PARALLEL…); se usan los valores por defecto");
    }

    // ------------------------------------------------------------------ sentencias
    static Imported custom(String sql, String schema, String description, String note) {
        return new Imported(map("type", "CUSTOM_SQL", "schema", schema, "description", head(description, 1000), "sql", sql),
                note == null ? List.of() : List.of(note));
    }

    @SuppressWarnings("unchecked")
    static List<Imported> parseCreateTable(String stmt, String ds) {
        Matcher m = at("^CREATE\\s+(GLOBAL\\s+TEMPORARY\\s+)?TABLE\\s+" + QNAME + "\\s*\\(", stmt, CI);
        if (m == null) throw new ParseError("CREATE TABLE sin definición de columnas (p. ej. AS SELECT) no soportado visualmente");
        String[] st = qname(m.group(2), m.group(3), ds);
        int end = matchingParen(stmt, m.end() - 1);
        String body = stmt.substring(m.end(), end), tail = stmt.substring(end + 1).strip();
        if (at("^AS\\b", tail, CI) != null || Pattern.compile("\\bORGANIZATION\\b", CI).matcher(tail).find())
            throw new ParseError("CREATE TABLE AS SELECT / tablas organizadas por índice no soportadas visualmente");
        List<String> notes = new ArrayList<>();
        List<Map<String, Object>> columns = new ArrayList<>();
        List<Map<String, Object>> constraints = new ArrayList<>();
        Map<String, Object> op = map("type", "CREATE_TABLE", "schema", st[0], "table", st[1], "comment", null,
                "columns", columns, "primary_key", new ArrayList<String>(), "primary_key_name", null, "constraints", constraints);
        if (m.group(1) != null && !Pattern.compile("ON\\s+COMMIT", CI).matcher(tail).find()) op.put("temporary", "DELETE_ROWS");
        parseTableOptions(tail, op, notes);
        for (String el : splitTop(body)) {
            List<Map<String, Object>> cons;
            if (at("^(CONSTRAINT\\s|PRIMARY\\s+KEY|UNIQUE\\s*\\(|FOREIGN\\s+KEY|CHECK\\s*\\()", el.strip(), CI) != null) {
                cons = List.of(parseTableConstraint(el, notes));
            } else if (at("^(SUPPLEMENTAL|PERIOD)\\b", el.strip(), CI) != null) {
                throw new ParseError("elemento de tabla no soportado: " + head(el, 40));
            } else {
                Object[] pc = parseColumn(el, notes);
                columns.add((Map<String, Object>) pc[0]);
                cons = (List<Map<String, Object>>) pc[1];
            }
            for (Map<String, Object> c : cons) {
                if ("PRIMARY_KEY".equals(c.get("kind"))) {
                    if (Boolean.FALSE.equals(c.get("enabled")) || Boolean.TRUE.equals(c.get("deferrable")) || Boolean.FALSE.equals(c.get("validated")))
                        throw new ParseError("llave primaria deshabilitada/diferible no soportada visualmente");
                    op.put("primary_key", c.get("columns"));
                    op.put("primary_key_name", c.get("name"));
                    op.put("primary_key_tablespace", c.get("index_tablespace"));
                } else {
                    constraints.add(c);
                }
            }
        }
        return List.of(new Imported(op, notes));
    }

    static List<Imported> parseCreateIndex(String stmt, String ds) {
        Matcher m = at("^CREATE\\s+(UNIQUE\\s+|BITMAP\\s+)?INDEX\\s+" + QNAME + "\\s+ON\\s+" + QNAME + "\\s*", stmt, CI);
        if (m == null) throw new ParseError("CREATE INDEX no reconocido");
        String[] si = qname(m.group(2), m.group(3), ds);
        String[] tt = qname(m.group(4), m.group(5), ds);
        if (!java.util.Objects.equals(tt[0], si[0])) throw new ParseError("índice en un esquema distinto al de la tabla");
        String[] cb = parenBody(stmt.substring(m.end()));
        String rest = cb[1];
        List<String> notes = new ArrayList<>();
        List<Map<String, Object>> cols = new ArrayList<>();
        Map<String, Object> op = map("type", "CREATE_INDEX", "schema", si[0], "table", tt[1], "index_name", si[1],
                "index_type", m.group(1) == null ? "NORMAL" : m.group(1).strip().toUpperCase(), "columns", cols);
        for (String c : splitTop(cb[0])) {
            Matcher dm = full("^(.*?)\\s+(ASC|DESC)$", c.strip(), CIS);
            String expr = dm != null ? dm.group(1) : c.strip();
            boolean desc = dm != null && dm.group(2).equalsIgnoreCase("DESC");
            expr = expr.strip().matches(NAME) ? unq(expr.strip()) : expr.strip();
            cols.add(map("expression", expr, "descending", desc));
        }
        Object[] t = take("INDEXTYPE\\s+IS\\s+(" + NAME + "(?:\\s*\\.\\s*" + NAME + ")?)", rest);
        rest = (String) t[1];
        if (t[0] != null) {
            op.put("index_type", "DOMAIN");
            List<String> parts = new ArrayList<>();
            for (String p : ((Matcher) t[0]).group(1).split("\\.")) parts.add(unq(p.strip()));
            op.put("indextype", String.join(".", parts));
            Object[] pt = take("PARAMETERS\\s*\\(\\s*'((?:[^']|'')*)'\\s*\\)", rest);
            rest = (String) pt[1];
            if (pt[0] != null) op.put("parameters", unescape(((Matcher) pt[0]).group(1)));
        }
        if (searchTop("GLOBAL\\s+PARTITION\\b", rest) != null) throw new ParseError("índices globales particionados no soportados visualmente");
        String[][] flags = {{"LOCAL\\b(\\s*\\(.*?\\)(?=\\s|$))?", "local"}, {"REVERSE\\b", "reverse"}, {"INVISIBLE\\b", "invisible"}, {"ONLINE\\b", "online"}};
        for (String[] f : flags) {
            Object[] mm = take(f[0], rest);
            rest = (String) mm[1];
            if (mm[0] != null) {
                op.put(f[1], true);
                if (f[1].equals("local") && ((Matcher) mm[0]).group(1) != null)
                    notes.add("Se ignoró la lista de particiones del índice LOCAL (Oracle las crea iguales a la tabla)");
            }
        }
        rest = (String) take("VISIBLE\\b", rest)[1];
        t = take("TABLESPACE\\s+(" + NAME + ")", rest);
        rest = (String) t[1];
        if (t[0] != null) op.put("tablespace", unq(((Matcher) t[0]).group(1)));
        rest = (String) take("NOCOMPRESS\\b", rest)[1];
        t = take("COMPRESS(\\s+ADVANCED\\s+(LOW|HIGH)|\\s+(\\d+))?\\b", rest);
        rest = (String) t[1];
        if (t[0] != null) {
            Matcher cm = (Matcher) t[0];
            if (cm.group(2) != null) op.put("compression", "ADVANCED_" + cm.group(2).toUpperCase());
            else {
                op.put("compression", "PREFIX");
                op.put("prefix_length", cm.group(3) != null ? Integer.parseInt(cm.group(3)) : null);
            }
        }
        rest = (String) take("(NO)?LOGGING\\b", rest)[1];
        if (!physicalLeft(rest).isEmpty()) throw new ParseError("opciones de índice no soportadas visualmente: " + head(physicalLeft(rest), 60));
        if (!rest.isBlank()) notes.add("Se ignoraron opciones físicas del índice (PCTFREE, PARALLEL…)");
        return List.of(new Imported(op, notes));
    }

    @SuppressWarnings("unchecked")
    static List<Imported> parseAlterTable(String stmt, String ds, ColumnLookup lookup) {
        Matcher m = full("^ALTER\\s+TABLE\\s+" + QNAME + "\\s+(.*)$", stmt, CIS);
        if (m == null) return null;
        String[] st = qname(m.group(1), m.group(2), ds);
        String schema = st[0], table = st[1];
        String action = m.group(3).strip();
        String up = action.toUpperCase().replaceAll("\\s+", " ");

        if (at("^ADD\\s+(CONSTRAINT\\b|PRIMARY\\s+KEY|UNIQUE\\s*\\(|FOREIGN\\s+KEY|CHECK\\s*\\()", action, CI) != null) {
            List<String> notes = new ArrayList<>();
            return List.of(new Imported(map("type", "ADD_CONSTRAINT", "schema", schema, "table", table,
                    "constraint", parseTableConstraint(action.substring(3).strip(), notes)), notes));
        }
        if (at("^ADD\\s+PARTITION\\b", action, CI) != null) {
            Matcher pm = full("^ADD\\s+PARTITION\\s+(" + NAME + ")\\s*(.*)$", action, CIS);
            if (pm == null) throw new ParseError("ADD PARTITION no reconocido");
            Map<String, Object> part = map("name", unq(pm.group(1)));
            String prest = pm.group(2);
            Matcher vm = at("^\\s*VALUES\\s+(LESS\\s+THAN\\s*)?", prest, CI);
            String method = "HASH";
            if (vm != null) {
                method = vm.group(1) != null ? "RANGE" : "LIST";
                String[] vb = parenBody(prest.substring(vm.end()));
                part.put("values", vb[0].strip());
                prest = vb[1];
            }
            Object[] tk = take("TABLESPACE\\s+(" + NAME + ")", prest);
            prest = (String) tk[1];
            if (tk[0] != null) part.put("tablespace", unq(((Matcher) tk[0]).group(1)));
            if (!physicalLeft(prest).isEmpty()) throw new ParseError("opciones de partición no soportadas: " + head(prest.strip(), 50));
            return List.of(new Imported(map("type", "ADD_PARTITION", "schema", schema, "table", table, "method", method, "partition", part)));
        }
        if (at("^ADD\\b", action, CI) != null) {
            String body = action.substring(3).strip();
            if (at("^COLUMN\\b", body, CI) != null) body = body.substring(6).strip();
            if (body.startsWith("(")) body = parenBody(body)[0];
            List<Imported> out = new ArrayList<>();
            for (String el : splitTop(body)) {
                List<String> notes = new ArrayList<>();
                Object[] pc = parseColumn(el, notes);
                out.add(new Imported(map("type", "ADD_COLUMN", "schema", schema, "table", table, "column", pc[0]), notes));
                for (Map<String, Object> c : (List<Map<String, Object>>) pc[1]) {
                    Imported it = new Imported(map("type", "ADD_CONSTRAINT", "schema", schema, "table", table, "constraint", c));
                    it.companion = true;
                    out.add(it);
                }
            }
            return out;
        }
        Matcher dm = full("^DROP\\s+(?:COLUMN\\s+(" + NAME + ")|\\(\\s*(" + NAME + ")\\s*\\))$", action, CI);
        if (dm != null) {
            return List.of(new Imported(map("type", "DROP_COLUMN", "schema", schema, "table", table,
                    "column_name", unq(dm.group(1) != null ? dm.group(1) : dm.group(2)))));
        }
        dm = full("^DROP\\s+CONSTRAINT\\s+(" + NAME + ")(\\s+CASCADE)?(\\s+(KEEP|DROP)\\s+INDEX)?$", action, CI);
        if (dm != null) {
            return List.of(new Imported(map("type", "DROP_CONSTRAINT", "schema", schema, "table", table,
                    "constraint_name", unq(dm.group(1)), "cascade", dm.group(2) != null,
                    "index", dm.group(4) != null ? dm.group(4).toUpperCase() : null)));
        }
        dm = full("^DROP\\s+PARTITION\\s+(" + NAME + ")(\\s+UPDATE\\s+(GLOBAL\\s+)?INDEXES)?$", action, CI);
        if (dm != null) {
            return List.of(new Imported(map("type", "DROP_PARTITION", "schema", schema, "table", table,
                    "partition_name", unq(dm.group(1)), "update_indexes", dm.group(2) != null)));
        }
        Matcher sm = full("^MODIFY\\s+CONSTRAINT\\s+(" + NAME + ")\\s+(ENABLE|DISABLE)(\\s+(NO)?VALIDATE)?$", action, CI);
        if (sm != null) {
            return List.of(new Imported(map("type", "CONSTRAINT_STATE", "schema", schema, "table", table,
                    "constraint_name", unq(sm.group(1)), "action", sm.group(2).toUpperCase(), "validate_existing", sm.group(4) == null)));
        }
        sm = full("^(ENABLE|DISABLE)(\\s+(NO)?VALIDATE)?\\s+CONSTRAINT\\s+(" + NAME + ")$", action, CI);
        if (sm != null) {
            return List.of(new Imported(map("type", "CONSTRAINT_STATE", "schema", schema, "table", table,
                    "constraint_name", unq(sm.group(4)), "action", sm.group(1).toUpperCase(), "validate_existing", sm.group(3) == null)));
        }
        Matcher rm = full("^RENAME\\s+(COLUMN|CONSTRAINT)\\s+(" + NAME + ")\\s+TO\\s+(" + NAME + ")$", action, CI);
        if (rm != null) {
            return List.of(new Imported(map("type", "RENAME", "schema", schema, "target", rm.group(1).toUpperCase(), "table", table,
                    "old_name", unq(rm.group(2)), "new_name", unq(rm.group(3)))));
        }
        rm = full("^RENAME\\s+TO\\s+(" + NAME + ")$", action, CI);
        if (rm != null) {
            return List.of(new Imported(map("type", "RENAME", "schema", schema, "target", "TABLE", "old_name", table, "new_name", unq(rm.group(1)))));
        }
        if (up.matches("^(NOLOGGING|LOGGING|READ ONLY|READ WRITE|NOCOMPRESS|(ROW STORE )?COMPRESS( BASIC| ADVANCED| FOR OLTP)?)$")) {
            Map<String, Object> opt = map("type", "ALTER_TABLE_OPTIONS", "schema", schema, "table", table);
            if (up.contains("LOGGING")) opt.put("logging", up.equals("LOGGING"));
            else if (up.contains("READ")) opt.put("read_only", up.equals("READ ONLY"));
            else opt.put("compression", up.equals("NOCOMPRESS") ? "NONE" : (up.contains("ADVANCED") || up.contains("OLTP")) ? "ADVANCED" : "BASIC");
            return List.of(new Imported(opt));
        }
        if (at("^MODIFY\\b", action, CI) != null
                && at("^MODIFY\\s+(PARTITION|LOB|DEFAULT\\s+ATTRIBUTES|CONSTRAINT|PRIMARY|UNIQUE)\\b", action, CI) == null) {
            String body = action.substring(6).strip();
            if (body.startsWith("(")) body = parenBody(body)[0];
            List<Imported> out = new ArrayList<>();
            for (String el : splitTop(body)) {
                Matcher cm = full("\\s*(" + NAME + ")\\s*(.*)$", el, Pattern.DOTALL);
                if (cm == null) throw new ParseError("MODIFY no reconocido");
                String name = unq(cm.group(1));
                String rest = cm.group(2);
                Map<String, Object> current = lookup.find(schema, table, name);
                if (current == null) throw new ParseError("no existe " + schema + "." + table + "." + name + " para tomar su definición actual");
                Map<String, Object> col = new LinkedHashMap<>(current);
                if (!rest.isBlank() && !CLAUSE_KW.matcher(rest.strip()).lookingAt()) {
                    Object[] tp = parseType(rest);
                    col.put("length", null);
                    col.put("precision", null);
                    col.put("scale", null);
                    col.put("char_semantics", false);
                    col.putAll((Map<String, Object>) tp[0]);
                    rest = (String) tp[1];
                }
                for (String[] kc : rest.isBlank() ? List.<String[]>of() : splitClauses(rest)) {
                    switch (kc[0]) {
                        case "DEFAULT" -> {
                            col.put("default_on_null", at("^DEFAULT\\s+ON\\s+NULL\\b", kc[1], CI) != null);
                            col.put("default", defaultExpr(kc[1]));
                        }
                        case "NOT NULL" -> col.put("nullable", false);
                        case "NULL" -> col.put("nullable", true);
                        case "INVISIBLE", "VISIBLE" -> col.put("invisible", kc[0].equals("INVISIBLE"));
                        default -> throw new ParseError("cláusula " + kc[0] + " en MODIFY no soportada visualmente");
                    }
                }
                out.add(new Imported(map("type", "MODIFY_COLUMN", "schema", schema, "table", table, "column", col, "previous", current)));
            }
            return out;
        }
        return null;
    }

    static List<Imported> parseSequence(String flat, String ds) {
        Matcher m = full("^(CREATE|ALTER) SEQUENCE " + QNAME + "(.*)$", flat, CI);
        if (m == null) return null;
        String[] sn = qname(m.group(2), m.group(3), ds);
        boolean create = m.group(1).equalsIgnoreCase("CREATE");
        Map<String, Object> op = map("type", create ? "CREATE_SEQUENCE" : "ALTER_SEQUENCE", "schema", sn[0], "name", sn[1]);
        String rest = " " + m.group(4) + " ";
        if (!create) {
            Matcher rm = Pattern.compile("\\bRESTART(?: START WITH (-?\\d+))?\\b", CI).matcher(rest);
            if (rm.find()) {
                op.put("restart_with", rm.group(1) != null ? Long.parseLong(rm.group(1)) : 1L);
                rest = rest.substring(0, rm.start()) + " " + rest.substring(rm.end());
            }
        }
        String[][] nums = {{"\\bSTART WITH (-?\\d+)", "start_with"}, {"\\bINCREMENT BY (-?\\d+)", "increment_by"},
                {"\\bMINVALUE (-?\\d+)", "min_value"}, {"\\bMAXVALUE (-?\\d+)", "max_value"}, {"\\bCACHE (\\d+)", "cache"}};
        for (String[] pk : nums) {
            Matcher mm = Pattern.compile(pk[0], CI).matcher(rest);
            if (mm.find()) {
                if (pk[1].equals("start_with") && !create) throw new ParseError("ALTER SEQUENCE START WITH no es válido; usa RESTART START WITH");
                op.put(pk[1], pk[1].equals("cache") ? (Object) Integer.parseInt(mm.group(1)) : (Object) Long.parseLong(mm.group(1)));
                rest = rest.substring(0, mm.start()) + " " + rest.substring(mm.end());
            }
        }
        Object[][] words = {{"NOCACHE", "cache", 0}, {"NOCYCLE", "cycle", false}, {"CYCLE", "cycle", true},
                {"NOORDER", "order", false}, {"ORDER", "order", true}};
        for (Object[] w : words) {
            Pattern p = Pattern.compile("\\b" + w[0] + "\\b", CI);
            if (p.matcher(rest).find()) {
                op.put((String) w[1], w[2]);
                rest = p.matcher(rest).replaceAll("");
            }
        }
        rest = rest.replaceAll("(?i)\\b(NOMINVALUE|NOMAXVALUE|NOKEEP|NOSCALE|NOSHARD)\\b", "").strip();
        if (!rest.isEmpty()) throw new ParseError("opciones de secuencia no soportadas: " + head(rest, 50));
        return List.of(new Imported(op));
    }

    private static final Pattern CODE = Pattern.compile(
            "^CREATE\\s+(?:OR\\s+REPLACE\\s+)?(?:FORCE\\s+|NO\\s+FORCE\\s+)?(?:EDITIONABLE\\s+|NONEDITIONABLE\\s+)?"
                    + "(PACKAGE\\s+BODY|PACKAGE|PROCEDURE|FUNCTION|TRIGGER|TYPE|MATERIALIZED\\s+VIEW(?!\\s+LOG)|VIEW)\\s+" + QNAME, CI);

    /** Convierte una sentencia. `ops` son las ya convertidas (para fusionar comentarios/privilegios). */
    @SuppressWarnings("unchecked")
    static List<Imported> parseStatement(String stmtRaw, String ds, List<Imported> ops, ColumnLookup lookup) {
        String stmt = stripComments(stmtRaw).replaceAll(";+$", "").strip();
        String flat = stmt.replaceAll("\\s+", " ");
        String up = flat.toUpperCase();

        Matcher code = CODE.matcher(stmt);
        if (code.lookingAt()) {
            String[] sn = qname(code.group(2), code.group(3), ds);
            return List.of(new Imported(map("type", "CREATE_OR_REPLACE_CODE", "schema", sn[0],
                    "kind", code.group(1).toUpperCase().replaceAll("\\s+", " "), "name", sn[1],
                    "source", stmtRaw.strip().replaceAll("/+$", "").strip())));
        }
        if (up.matches("^CREATE (GLOBAL TEMPORARY )?TABLE .*")) return parseCreateTable(stmt, ds);
        if (up.matches("^CREATE (UNIQUE |BITMAP )?INDEX .*")) return parseCreateIndex(stmt, ds);

        Matcher m = full("^COMMENT ON (TABLE|MATERIALIZED VIEW) " + QNAME + " IS '((?:[^']|'')*)'$", flat, CI);
        if (m != null) {
            String[] st = qname(m.group(2), m.group(3), ds);
            for (Imported it : ops) {
                if ("CREATE_TABLE".equals(it.op.get("type")) && st[0] != null && st[0].equals(it.op.get("schema")) && st[1].equals(it.op.get("table"))) {
                    it.op.put("comment", unescape(m.group(4)));
                    it.absorbed.add(stmt);
                    return List.of();
                }
            }
            return List.of(new Imported(map("type", "SET_COMMENT", "schema", st[0], "table", st[1], "comment", unescape(m.group(4)))));
        }

        m = full("^COMMENT ON COLUMN (" + NAME + ") ?\\. ?(" + NAME + ")(?: ?\\. ?(" + NAME + "))? IS '((?:[^']|'')*)'$", flat, CI);
        if (m != null) {
            String schema, table, col;
            if (m.group(3) != null) {
                schema = unq(m.group(1));
                table = unq(m.group(2));
                col = unq(m.group(3));
            } else {
                schema = ds;
                table = unq(m.group(1));
                col = unq(m.group(2));
            }
            for (Imported it : ops) {
                Map<String, Object> o = it.op;
                if (schema != null && schema.equals(o.get("schema")) && table.equals(o.get("table"))) {
                    List<Map<String, Object>> cols = "CREATE_TABLE".equals(o.get("type")) ? (List<Map<String, Object>>) o.get("columns")
                            : "ADD_COLUMN".equals(o.get("type")) ? List.of((Map<String, Object>) o.get("column")) : List.of();
                    for (Map<String, Object> c : cols) {
                        if (col.equals(c.get("name"))) {
                            c.put("comment", unescape(m.group(4)));
                            it.absorbed.add(stmt);
                            return List.of();
                        }
                    }
                }
            }
            return List.of(new Imported(map("type", "SET_COMMENT", "schema", schema, "table", table, "column", col, "comment", unescape(m.group(4)))));
        }

        m = full("^(GRANT|REVOKE) (.+?) ON " + QNAME + " (TO|FROM) (.+?)( WITH GRANT OPTION)?$", flat, CI);
        if (m != null) {
            String verb = m.group(1).toUpperCase();
            String[] so = qname(m.group(3), m.group(4), ds);
            Map<List<String>, List<String>> groups = new LinkedHashMap<>();
            for (String item : splitTop(m.group(2))) {
                Matcher pm = full("^([A-Za-z ]+?)\\s*(\\((.*)\\))?$", item.strip(), 0);
                if (pm == null) return List.of(custom(stmt, so[0], verb + " sobre " + so[0] + "." + so[1], "Privilegio no reconocido"));
                String priv = pm.group(1).toUpperCase().replaceAll("\\s+", " ");
                if (!Ops.OBJECT_PRIVILEGES.contains(priv))
                    return List.of(custom(stmt, so[0], verb + " sobre " + so[0] + "." + so[1], "Privilegio " + priv + " no soportado visualmente"));
                List<String> cols = pm.group(3) != null ? names(pm.group(3)) : List.of();
                groups.computeIfAbsent(cols, x -> new ArrayList<>()).add(priv);
            }
            List<Imported> out = new ArrayList<>();
            for (String g : splitTop(m.group(6))) {
                for (Map.Entry<List<String>, List<String>> en : groups.entrySet()) {
                    Map<String, Object> op = map("type", verb, "schema", so[0], "object", so[1], "grantee", unq(g.strip()),
                            "privileges", new ArrayList<>(en.getValue()), "columns", new ArrayList<>(en.getKey()));
                    if (verb.equals("GRANT")) op.put("with_grant_option", m.group(7) != null);
                    out.add(new Imported(op));
                }
            }
            return out;
        }

        m = full("^GRANT (.+?) TO (.+?)( WITH ADMIN OPTION)?$", flat, CI);
        if (m != null && m.group(3) == null) {
            List<String> grantees = splitTop(m.group(2)).stream().map(g -> unq(g.strip())).toList();
            List<String> items = new ArrayList<>();
            for (String p : m.group(1).split(",")) items.add(p.strip().toUpperCase().replaceAll("\\s+", " "));
            List<Imported> targets = ops.stream().filter(it -> "CREATE_USER".equals(it.op.get("type")) && grantees.contains(it.op.get("schema"))).toList();
            boolean valid = items.stream().allMatch(p -> Ops.SYSTEM_PRIVILEGES.contains(p) || p.matches("^[A-Z][A-Z0-9_$#]*$"));
            if (targets.size() == grantees.size() && valid) {
                targets.get(0).absorbed.add(stmt);
                for (Imported it : targets) {
                    List<String> sp = (List<String>) it.op.get("system_privileges");
                    List<String> roles = (List<String>) it.op.get("roles");
                    for (String p : items) {
                        if (Ops.SYSTEM_PRIVILEGES.contains(p)) {
                            if (!sp.contains(p)) sp.add(p);
                        } else roles.add(p);
                    }
                }
                return List.of();
            }
            return List.of(custom(stmt, grantees.get(0), "Privilegios de sistema para " + String.join(", ", grantees), null));
        }

        m = full("^CREATE (OR REPLACE )?(PUBLIC )?SYNONYM " + QNAME + " FOR " + QNAME + "(@\\S+)?$", flat, CI);
        if (m != null && m.group(7) == null) {
            String[] os = m.group(2) != null ? new String[]{"PUBLIC", unq(m.group(3))} : qname(m.group(3), m.group(4), ds);
            String[] tt = qname(m.group(5), m.group(6), ds);
            return List.of(new Imported(map("type", "CREATE_SYNONYM", "schema", os[0], "synonym", os[1], "target_schema", tt[0],
                    "target_object", tt[1], "replace", m.group(1) != null)));
        }

        m = full("^DROP (PUBLIC )?SYNONYM " + QNAME + "( FORCE)?$", flat, CI);
        if (m != null) {
            String[] os = m.group(1) != null ? new String[]{"PUBLIC", unq(m.group(2))} : qname(m.group(2), m.group(3), ds);
            return List.of(new Imported(map("type", "DROP_SYNONYM", "schema", os[0], "synonym", os[1])));
        }

        m = full("^DROP TABLE " + QNAME + "( CASCADE CONSTRAINTS)?( PURGE)?$", flat, CI);
        if (m != null) {
            String[] st = qname(m.group(1), m.group(2), ds);
            return List.of(new Imported(map("type", "DROP_TABLE", "schema", st[0], "table", st[1],
                    "cascade_constraints", m.group(3) != null, "purge", m.group(4) != null)));
        }

        m = full("^DROP INDEX " + QNAME + "( ONLINE)?( FORCE)?$", flat, CI);
        if (m != null) {
            String[] si = qname(m.group(1), m.group(2), ds);
            return List.of(new Imported(map("type", "DROP_INDEX", "schema", si[0], "index_name", si[1], "online", m.group(3) != null)));
        }

        m = full("^ALTER INDEX " + QNAME + " (.+)$", flat, CI);
        if (m != null) {
            String[] si = qname(m.group(1), m.group(2), ds);
            String act = m.group(3).strip();
            Matcher rn = full("^RENAME TO (" + NAME + ")$", act, CI);
            if (rn != null) return List.of(new Imported(map("type", "RENAME", "schema", si[0], "target", "INDEX", "old_name", si[1], "new_name", unq(rn.group(1)))));
            if (act.equalsIgnoreCase("VISIBLE") || act.equalsIgnoreCase("INVISIBLE"))
                return List.of(new Imported(map("type", "ALTER_INDEX", "schema", si[0], "index_name", si[1], "action", act.toUpperCase())));
            Matcher rb = full("^REBUILD(?: TABLESPACE (" + NAME + "))?( ONLINE)?$", act, CI);
            if (rb != null) {
                return List.of(new Imported(map("type", "ALTER_INDEX", "schema", si[0], "index_name", si[1], "action", "REBUILD",
                        "tablespace", rb.group(1) != null ? unq(rb.group(1)) : null, "online", rb.group(2) != null)));
            }
        }

        m = full("^RENAME (" + NAME + ") TO (" + NAME + ")$", flat, CI);
        if (m != null && ds != null && !ds.isEmpty()) {
            return List.of(new Imported(map("type", "RENAME", "schema", ds, "target", "TABLE", "old_name", unq(m.group(1)), "new_name", unq(m.group(2)))));
        }

        m = full("^DROP (PROCEDURE|FUNCTION|PACKAGE BODY|PACKAGE|MATERIALIZED VIEW|VIEW|SEQUENCE|TRIGGER|TYPE) " + QNAME + "$", flat, CI);
        if (m != null) {
            String[] sn = qname(m.group(2), m.group(3), ds);
            return List.of(new Imported(map("type", "DROP_CODE", "schema", sn[0], "kind", m.group(1).toUpperCase(), "name", sn[1])));
        }

        m = full("^CREATE MATERIALIZED VIEW LOG ON " + QNAME + "(.*)$", flat, CI);
        if (m != null) {
            String[] st = qname(m.group(1), m.group(2), ds);
            String rest = m.group(3);
            Map<String, Object> op = map("type", "CREATE_MV_LOG", "schema", st[0], "table", st[1], "with_primary_key", false);
            Matcher wm = Pattern.compile("\\bWITH ([A-Z ,]+?)(?:\\s*\\(([^)]*)\\))?(?= INCLUDING| EXCLUDING|$)", CI).matcher(rest);
            if (wm.find()) {
                List<String> kinds = new ArrayList<>();
                for (String k : wm.group(1).split(",")) if (!k.isBlank()) kinds.add(k.strip().toUpperCase().replaceAll("\\s+", " "));
                op.put("with_primary_key", kinds.contains("PRIMARY KEY"));
                op.put("with_rowid", kinds.contains("ROWID"));
                op.put("with_sequence", kinds.contains("SEQUENCE"));
                if (wm.group(2) != null) op.put("columns", names(wm.group(2)));
                rest = rest.substring(0, wm.start()) + rest.substring(wm.end());
            } else {
                op.put("with_primary_key", true);  // valor por defecto de Oracle
            }
            if (Pattern.compile("INCLUDING NEW VALUES", CI).matcher(rest).find()) op.put("including_new_values", true);
            rest = rest.replaceAll("(?i)(INCLUDING|EXCLUDING) NEW VALUES", "");
            if (!physicalLeft(rest.replaceAll("(?i)TABLESPACE " + NAME, "")).isEmpty())
                throw new ParseError("opciones del log no soportadas: " + head(rest.strip(), 50));
            return List.of(new Imported(op));
        }

        m = full("^DROP MATERIALIZED VIEW LOG ON " + QNAME + "$", flat, CI);
        if (m != null) {
            String[] st = qname(m.group(1), m.group(2), ds);
            return List.of(new Imported(map("type", "DROP_MV_LOG", "schema", st[0], "table", st[1])));
        }

        List<Imported> seq = parseSequence(flat, ds);
        if (seq != null) return seq;

        List<Imported> alt = parseAlterTable(stmt, ds, lookup);
        if (alt != null) return alt;

        m = full("^CREATE USER (" + NAME + ") IDENTIFIED BY (\"[^\"]*\"|\\S+)(.*)$", flat, CI);
        if (m != null) {
            String user = unq(m.group(1)), rest = m.group(3);
            Matcher ts = Pattern.compile("DEFAULT TABLESPACE (" + NAME + ")", CI).matcher(rest);
            Matcher quota = Pattern.compile("QUOTA (UNLIMITED|\\d+[KMG]) ON (" + NAME + ")", CI).matcher(rest);
            boolean hasTs = ts.find(), hasQuota = quota.find();
            List<String> notes = new ArrayList<>(List.of("La contraseña del script se ignora: DBA Manager genera una al ejecutar"));
            String leftover = rest.replaceAll("(?i)DEFAULT TABLESPACE " + NAME + "|QUOTA (UNLIMITED|\\d+[KMG]) ON " + NAME
                    + "|TEMPORARY TABLESPACE " + NAME + "|PASSWORD EXPIRE|ACCOUNT (UNLOCK|LOCK)", "").strip();
            if (!leftover.isEmpty()) notes.add("Opciones de usuario ignoradas: " + head(leftover, 80));
            return List.of(new Imported(map("type", "CREATE_USER", "schema", user, "kind", hasQuota ? "OWNER" : "READER",
                    "default_tablespace", hasTs ? unq(ts.group(1)) : (hasQuota ? unq(quota.group(2)) : null),
                    "quota", hasQuota ? quota.group(1).toUpperCase() : null,
                    "system_privileges", new ArrayList<String>(), "roles", new ArrayList<String>()), notes));
        }

        Matcher first = Pattern.compile("^\\w+(?: \\w+)?(?: " + QNAME + ")?").matcher(flat);
        String schema = ds;
        Matcher qm = Pattern.compile("\\b(" + NAME + ") ?\\. ?" + NAME).matcher(flat);
        if (qm.find()) {
            try {
                schema = unq(qm.group(1));
            } catch (ParseError ignored) {
                // se conserva el esquema por defecto
            }
        }
        return List.of(custom(stmtRaw.strip(), schema, "Importado: " + (first.lookingAt() ? head(first.group(0), 80) : head(flat, 80)),
                "No se pudo convertir a operación visual; queda como SQL personalizado"));
    }

    public static List<Imported> importScript(String sql, String defaultSchema, ColumnLookup lookup) {
        String ds = defaultSchema == null ? "" : defaultSchema.strip().toUpperCase();
        String dsOrNull = ds.isEmpty() ? null : ds;
        List<Imported> result = new ArrayList<>();
        for (String stmt : splitScript(sql)) {
            try {
                List<Imported> added = parseStatement(stmt, dsOrNull, result, lookup);
                for (Imported it : added) if (it.source.isEmpty()) it.source = stripComments(stmt).replaceAll(";+$", "").strip();
                result.addAll(added);
            } catch (ParseError | IndexOutOfBoundsException | NumberFormatException | IllegalStateException | ClassCastException
                     | NullPointerException exc) {
                String src = stripComments(stmt).replaceAll(";+$", "").strip();
                result.add(custom(src.isEmpty() ? stmt : src, dsOrNull, "Importado del script", "Queda como SQL personalizado: " + exc.getMessage()));
            }
        }
        for (Imported it : result) {
            Object s = it.op.get("schema");
            if (s == null || s.toString().isEmpty()) it.notes.add("Sin esquema en el script: indícalo en la operación");
        }
        return result;
    }
}
