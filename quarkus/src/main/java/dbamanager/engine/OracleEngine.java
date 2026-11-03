package dbamanager.engine;

import dbamanager.core.Crypto;
import dbamanager.ops.Ops.Op;

import java.io.Reader;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Motor Oracle (JDBC thin). La exploración consulta exclusivamente vistas del diccionario (DBA_*)
 * y DBMS_METADATA con la cuenta de catálogo, que no tiene SELECT ANY TABLE: aunque hubiera un
 * error, no podría leer datos.
 */
public final class OracleEngine {
    public static final String KEY = "oracle";
    public static final String LABEL = "Oracle Database";
    static final Set<String> OBJECT_TYPES = Set.of("TABLE", "VIEW", "MATERIALIZED VIEW", "SEQUENCE", "PROCEDURE", "FUNCTION",
            "PACKAGE", "SYNONYM", "TRIGGER", "TYPE");
    private static final Map<String, String> DDL_TYPE = Map.of("MATERIALIZED VIEW", "MATERIALIZED_VIEW", "PACKAGE BODY", "PACKAGE_BODY");
    private static final Set<String> ALWAYS_EXCLUDED = Set.of("PDBADMIN");
    private static final String INTERNAL_FILTER = """
             AND o.object_name NOT LIKE 'ISEQ$$%' AND o.object_name NOT LIKE 'MLOG$%' AND o.object_name NOT LIKE 'RUPD$%'
             AND NOT (o.object_type = 'TABLE' AND EXISTS (SELECT 1 FROM dba_mviews mv
                                                           WHERE mv.owner = o.owner AND mv.container_name = o.object_name))""";

    private OracleEngine() {}

    // ------------------------------------------------------------------ conexión
    public static Connection connect(ConnInfo info, String action, int callTimeoutSeconds) throws SQLException {
        Properties p = new Properties();
        p.setProperty("user", info.user());
        p.setProperty("password", info.password() == null ? "" : info.password());
        p.setProperty("oracle.net.CONNECT_TIMEOUT", "10000");
        p.setProperty("oracle.jdbc.ReadTimeout", String.valueOf(callTimeoutSeconds * 1000L));
        p.setProperty("v$session.program", "DBA Manager");
        Connection c = DriverManager.getConnection(info.jdbcUrl(), p);
        try {
            c.setAutoCommit(false);
            c.setClientInfo("OCSID.MODULE", "DBA Manager");
            c.setClientInfo("OCSID.ACTION", action.length() > 32 ? action.substring(0, 32) : action);
            if (info.appUser() != null) c.setClientInfo("OCSID.CLIENTID", info.appUser().length() > 64 ? info.appUser().substring(0, 64) : info.appUser());
        } catch (Exception ignored) {
            // la información de sesión es solo para auditoría del lado de Oracle
        }
        return c;
    }

    static Connection connect(ConnInfo info, String action) throws SQLException {
        return connect(info, action, 60);
    }

    /** Consulta con binds por nombre (:nombre). Llaves de cada fila en minúsculas. */
    public static List<Map<String, Object>> query(Connection c, String sql, Object... binds) {
        Map<String, Object> named = new LinkedHashMap<>();
        for (int i = 0; i < binds.length; i += 2) named.put((String) binds[i], binds[i + 1]);
        List<Object> values = new ArrayList<>();
        String jdbc = toPositional(sql, named, values);
        try (PreparedStatement ps = c.prepareStatement(jdbc)) {
            ps.setQueryTimeout(60);
            for (int i = 0; i < values.size(); i++) ps.setObject(i + 1, values.get(i));
            try (ResultSet rs = ps.executeQuery()) {
                return rows(rs);
            }
        } catch (SQLException e) {
            throw new DbError(e);
        }
    }

    /** Error de la base dentro de una lambda (sin checked exceptions). */
    public static final class DbError extends RuntimeException {
        public final SQLException sql;

        DbError(SQLException e) {
            super(e.getMessage(), e);
            this.sql = e;
        }
    }

    private static final Pattern BIND = Pattern.compile("'(?:[^']|'')*'|:([A-Za-z][A-Za-z0-9_]*)");

    static String toPositional(String sql, Map<String, Object> named, List<Object> values) {
        Matcher m = BIND.matcher(sql);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            if (m.group(1) == null) {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group()));
                continue;
            }
            if (!named.containsKey(m.group(1))) throw new IllegalStateException("Falta el bind :" + m.group(1));
            values.add(named.get(m.group(1)));
            m.appendReplacement(sb, "?");
        }
        m.appendTail(sb);
        return sb.toString();
    }

    static List<Map<String, Object>> rows(ResultSet rs) throws SQLException {
        ResultSetMetaData md = rs.getMetaData();
        List<Map<String, Object>> out = new ArrayList<>();
        while (rs.next()) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 1; i <= md.getColumnCount(); i++) row.put(md.getColumnLabel(i).toLowerCase(), value(rs, i, md.getColumnType(i)));
            out.add(row);
        }
        return out;
    }

    private static Object value(ResultSet rs, int i, int type) throws SQLException {
        return switch (type) {
            case java.sql.Types.CLOB, java.sql.Types.NCLOB -> {
                Clob clob = rs.getClob(i);
                yield clob == null ? null : clob.getSubString(1, (int) Math.min(clob.length(), 10_000_000));
            }
            case java.sql.Types.LONGVARCHAR, java.sql.Types.LONGNVARCHAR -> rs.getString(i);
            case java.sql.Types.NUMERIC, java.sql.Types.DECIMAL -> {
                java.math.BigDecimal d = rs.getBigDecimal(i);
                if (d == null) yield null;
                try {
                    yield d.longValueExact();
                } catch (ArithmeticException e) {
                    yield d;
                }
            }
            case java.sql.Types.DATE, java.sql.Types.TIMESTAMP, java.sql.Types.TIMESTAMP_WITH_TIMEZONE, -101, -102 -> {
                Timestamp t = rs.getTimestamp(i);
                yield t == null ? null : t.toLocalDateTime().toString();
            }
            default -> {
                Object o = rs.getObject(i);
                yield o == null || o instanceof String || o instanceof Number || o instanceof Boolean ? o : rs.getString(i);
            }
        };
    }

    // ------------------------------------------------------------------ utilidades
    private static List<String> excluded(ConnInfo info) {
        Set<String> s = new TreeSet<>(ALWAYS_EXCLUDED);
        info.excludedSchemas().forEach(x -> s.add(x.toUpperCase()));
        return new ArrayList<>(s);
    }

    /** NOT IN (:x0, :x1...) y sus binds. */
    private static Object[] notIn(List<String> excluded, List<Object> binds) {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < excluded.size(); i++) {
            names.add(":x" + i);
            binds.add("x" + i);
            binds.add(excluded.get(i));
        }
        return new Object[]{String.join(", ", names)};
    }

    /** El explorador no muestra esquemas internos de Oracle ni las cuentas de servicio de DBA Manager. */
    private static void checkSchema(Connection c, ConnInfo info, String owner) {
        if (excluded(info).contains(owner) || !query(c, "SELECT 1 FROM dba_users WHERE username = :u AND oracle_maintained = 'Y'", "u", owner).isEmpty())
            throw new SecurityException("El esquema " + owner + " no está disponible en el explorador");
    }

    // ------------------------------------------------------------------ conexión / versión
    public static Map<String, Object> testConnection(ConnInfo info) throws SQLException {
        try (Connection c = connect(info, "test")) {
            Map<String, Object> row;
            try {  // v$version requiere SELECT_CATALOG_ROLE; la cuenta ejecutora puede no tenerlo
                List<Map<String, Object>> r = query(c, """
                        SELECT v.banner, SYS_CONTEXT('USERENV','CON_NAME') AS container
                          FROM v$version v WHERE v.banner LIKE 'Oracle%' AND ROWNUM = 1""");
                row = r.isEmpty() ? Map.of() : r.get(0);
            } catch (DbError e) {
                row = query(c, "SELECT NULL AS banner, SYS_CONTEXT('USERENV','CON_NAME') AS container FROM dual").get(0);
            }
            String version = version(c);
            int[] v = OracleDdl.parseVersion(version);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("version", version);
            out.put("supported", v == null || !OracleDdl.lt(v, OracleDdl.MIN_VERSION));
            out.put("banner", row.get("banner"));
            out.put("container", row.get("container"));
            out.put("session_privileges", query(c, "SELECT privilege FROM session_privs ORDER BY 1").stream().map(p -> p.get("privilege")).toList());
            return out;
        }
    }

    /** Versión completa del servidor (p. ej. 19.21.0.0.0). */
    static String version(Connection c) {
        List<Map<String, Object>> r;
        try {
            r = query(c, "SELECT version_full AS v FROM product_component_version WHERE product LIKE 'Oracle%' AND ROWNUM = 1");
        } catch (DbError e) {  // version_full existe desde 18c
            r = query(c, "SELECT version AS v FROM product_component_version WHERE product LIKE 'Oracle%' AND ROWNUM = 1");
        }
        if (!r.isEmpty() && r.get(0).get("v") != null) return r.get(0).get("v").toString();
        try {
            return c.getMetaData().getDatabaseProductVersion().replaceAll("(?s).*?(\\d+(\\.\\d+)+).*", "$1");
        } catch (SQLException e) {
            return null;
        }
    }

    public static String serverVersion(ConnInfo info) throws SQLException {
        try (Connection c = connect(info, "version")) {
            return version(c);
        }
    }

    /** Cuáles de estos esquemas son internos de Oracle según el diccionario. */
    public static Set<String> oracleMaintained(ConnInfo info, Set<String> names) throws SQLException {
        if (names.isEmpty()) return Set.of();
        List<Object> binds = new ArrayList<>();
        List<String> marks = new ArrayList<>();
        int i = 0;
        for (String n : new TreeSet<>(names)) {
            marks.add(":u" + i);
            binds.add("u" + i++);
            binds.add(n);
        }
        try (Connection c = connect(info, "guard")) {
            return query(c, "SELECT username FROM dba_users WHERE oracle_maintained = 'Y' AND username IN (" + String.join(", ", marks) + ")",
                    binds.toArray()).stream().map(r -> (String) r.get("username")).collect(Collectors.toSet());
        }
    }

    // ------------------------------------------------------------------ exploración
    public static List<Map<String, Object>> listSchemas(ConnInfo info) throws SQLException {
        List<Object> binds = new ArrayList<>();
        String notIn = (String) notIn(excluded(info), binds)[0];
        String sql = """
                SELECT u.username AS name, u.account_status, u.created, u.default_tablespace,
                       (SELECT COUNT(*) FROM dba_objects o WHERE o.owner = u.username AND o.object_type = 'TABLE'
                          AND o.generated = 'N' AND o.object_name NOT LIKE 'BIN$%%' %1$s) AS tables,
                       (SELECT COUNT(*) FROM dba_objects o WHERE o.owner = u.username
                          AND o.object_type IN ('TABLE','VIEW','MATERIALIZED VIEW','SEQUENCE','PROCEDURE','FUNCTION',
                                                'PACKAGE','SYNONYM','TRIGGER','TYPE')
                          AND o.generated = 'N' AND o.object_name NOT LIKE 'BIN$%%' %1$s) AS objects,
                       (SELECT COUNT(*) FROM dba_tab_privs p WHERE p.grantee = u.username) AS received_grants
                  FROM dba_users u
                 WHERE u.oracle_maintained = 'N' AND u.username NOT IN (%2$s)
                 ORDER BY u.username""".formatted(INTERNAL_FILTER, notIn);
        try (Connection c = connect(info, "list_schemas")) {
            List<Map<String, Object>> rows = query(c, sql, binds.toArray());
            for (Map<String, Object> r : rows) {
                long tables = ((Number) r.get("tables")).longValue();
                long grants = ((Number) r.get("received_grants")).longValue();
                // Sin tablas propias pero con permisos recibidos => usuario lector
                r.put("kind", tables == 0 && grants > 0 ? "READER" : tables > 0 ? "OWNER" : "EMPTY");
            }
            return rows;
        }
    }

    public static List<Map<String, Object>> listObjects(ConnInfo info, String schema) throws SQLException {
        String sql = """
                SELECT o.object_name AS name, o.object_type AS type, o.status, o.created, o.last_ddl_time,
                       tc.comments AS comments, s.table_owner AS target_schema, s.table_name AS target_object
                  FROM dba_objects o
                  LEFT JOIN dba_tab_comments tc ON tc.owner = o.owner AND tc.table_name = o.object_name
                                               AND o.object_type IN ('TABLE','VIEW')
                  LEFT JOIN dba_synonyms s ON s.owner = o.owner AND s.synonym_name = o.object_name
                                          AND o.object_type = 'SYNONYM'
                 WHERE o.owner = :owner AND o.generated = 'N' AND o.object_name NOT LIKE 'BIN$%'""" + INTERNAL_FILTER + """

                   AND o.object_type IN ('TABLE','VIEW','MATERIALIZED VIEW','SEQUENCE','PROCEDURE','FUNCTION',
                                         'PACKAGE','SYNONYM','TRIGGER','TYPE')
                 ORDER BY o.object_type, o.object_name""";
        try (Connection c = connect(info, "list_objects")) {
            checkSchema(c, info, schema);
            return query(c, sql, "owner", schema);
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> describeTable(ConnInfo info, String owner, String table) throws SQLException {
        Object[] b = {"owner", owner, "name", table};
        List<Map<String, Object>> obj, columns, constraints, referencedBy, indexes, indCols, grants, synonyms, part, partitions, triggers, mvLog;
        try (Connection c = connect(info, "describe_table")) {
            checkSchema(c, info, owner);
            obj = query(c, """
                    SELECT o.object_type AS type, o.status, o.created, o.last_ddl_time, tc.comments,
                           t.tablespace_name, t.partitioned, t.temporary, t.duration, t.compression, t.compress_for,
                           t.logging, t.read_only, t.iot_type
                      FROM dba_objects o
                      LEFT JOIN dba_tab_comments tc ON tc.owner = o.owner AND tc.table_name = o.object_name
                      LEFT JOIN dba_tables t ON t.owner = o.owner AND t.table_name = o.object_name
                     WHERE o.owner = :owner AND o.object_name = :name
                       AND o.object_type IN ('TABLE','VIEW','MATERIALIZED VIEW')
                     ORDER BY DECODE(o.object_type,'MATERIALIZED VIEW',1,'TABLE',2,3)""", b);
            if (obj.isEmpty()) throw new java.util.NoSuchElementException("No existe la tabla o vista " + owner + "." + table);
            columns = query(c, """
                    SELECT c.column_id, c.column_name AS name, c.data_type, c.data_length, c.char_length,
                           c.char_used, c.data_precision AS precision, c.data_scale AS scale,
                           c.nullable, c.data_default AS default_value, c.default_on_null, c.identity_column,
                           c.virtual_column, c.hidden_column, cc.comments,
                           ic.generation_type, ic.identity_options
                      FROM dba_tab_cols c
                      LEFT JOIN dba_col_comments cc ON cc.owner = c.owner AND cc.table_name = c.table_name
                                                   AND cc.column_name = c.column_name
                      LEFT JOIN dba_tab_identity_cols ic ON ic.owner = c.owner AND ic.table_name = c.table_name
                                                        AND ic.column_name = c.column_name
                     WHERE c.owner = :owner AND c.table_name = :name AND c.user_generated = 'YES'
                     ORDER BY c.column_id NULLS LAST, c.internal_column_id""", b);
            constraints = query(c, """
                    SELECT c.constraint_name AS name, c.constraint_type AS type, c.search_condition AS condition,
                           c.status, c.delete_rule, c.deferrable, c.deferred, c.validated, c.generated, c.index_name,
                           r.owner AS ref_owner, r.table_name AS ref_table,
                           (SELECT LISTAGG(cc.column_name, ', ') WITHIN GROUP (ORDER BY cc.position)
                              FROM dba_cons_columns cc
                             WHERE cc.owner = c.owner AND cc.constraint_name = c.constraint_name) AS columns,
                           (SELECT LISTAGG(rc.column_name, ', ') WITHIN GROUP (ORDER BY rc.position)
                              FROM dba_cons_columns rc
                             WHERE rc.owner = c.r_owner AND rc.constraint_name = c.r_constraint_name) AS ref_columns
                      FROM dba_constraints c
                      LEFT JOIN dba_constraints r ON r.owner = c.r_owner AND r.constraint_name = c.r_constraint_name
                     WHERE c.owner = :owner AND c.table_name = :name AND c.constraint_type IN ('P','U','R','C')
                     ORDER BY DECODE(c.constraint_type,'P',1,'U',2,'R',3,4), c.constraint_name""", b);
            referencedBy = query(c, """
                    SELECT c.owner, c.table_name, c.constraint_name AS name,
                           (SELECT LISTAGG(cc.column_name, ', ') WITHIN GROUP (ORDER BY cc.position)
                              FROM dba_cons_columns cc
                             WHERE cc.owner = c.owner AND cc.constraint_name = c.constraint_name) AS columns
                      FROM dba_constraints c
                      JOIN dba_constraints p ON p.owner = c.r_owner AND p.constraint_name = c.r_constraint_name
                     WHERE c.constraint_type = 'R' AND p.owner = :owner AND p.table_name = :name
                     ORDER BY c.owner, c.table_name""", b);
            indexes = query(c, """
                    SELECT i.owner, i.index_name AS name, i.index_type AS type, i.uniqueness, i.status, i.visibility,
                           i.compression, i.prefix_length, i.partitioned, i.tablespace_name, i.ityp_owner, i.ityp_name,
                           i.parameters, i.locality,
                           (SELECT c.constraint_name FROM dba_constraints c
                             WHERE c.index_owner = i.owner AND c.index_name = i.index_name AND ROWNUM = 1) AS constraint_name
                      FROM (SELECT x.*, (SELECT locality FROM dba_part_indexes pi
                                          WHERE pi.owner = x.owner AND pi.index_name = x.index_name) AS locality
                              FROM dba_indexes x) i
                     WHERE i.table_owner = :owner AND i.table_name = :name AND i.index_type <> 'LOB'
                     ORDER BY i.index_name""", b);
            indCols = query(c, """
                    SELECT ic.index_owner, ic.index_name, ic.column_position, ic.column_name, ic.descend, e.column_expression
                      FROM dba_ind_columns ic
                      LEFT JOIN dba_ind_expressions e ON e.index_owner = ic.index_owner AND e.index_name = ic.index_name
                                                     AND e.column_position = ic.column_position
                     WHERE ic.table_owner = :owner AND ic.table_name = :name
                     ORDER BY ic.index_name, ic.column_position""", b);
            grants = query(c, """
                    SELECT grantee, privilege, grantable, grantor, NULL AS column_name
                      FROM dba_tab_privs WHERE owner = :owner AND table_name = :name
                    UNION ALL
                    SELECT grantee, privilege, grantable, grantor, column_name
                      FROM dba_col_privs WHERE owner = :owner AND table_name = :name
                     ORDER BY 1, 2""", b);
            synonyms = query(c, """
                    SELECT owner, synonym_name AS name FROM dba_synonyms
                     WHERE table_owner = :owner AND table_name = :name ORDER BY owner, synonym_name""", b);
            part = query(c, """
                    SELECT p.partitioning_type, p.subpartitioning_type, p.interval, p.partition_count,
                           p.def_tablespace_name, p.def_compression, p.def_compress_for, p.def_logging,
                           (SELECT LISTAGG(k.column_name, ', ') WITHIN GROUP (ORDER BY k.column_position)
                              FROM dba_part_key_columns k
                             WHERE k.owner = p.owner AND k.name = p.table_name AND k.object_type = 'TABLE') AS columns
                      FROM dba_part_tables p WHERE p.owner = :owner AND p.table_name = :name""", b);
            partitions = part.isEmpty() ? List.of() : query(c, """
                    SELECT partition_name AS name, partition_position AS position, high_value, tablespace_name, interval
                      FROM dba_tab_partitions WHERE table_owner = :owner AND table_name = :name
                     ORDER BY partition_position FETCH FIRST 300 ROWS ONLY""", b);
            triggers = query(c, """
                    SELECT owner, trigger_name AS name, trigger_type, triggering_event, status
                      FROM dba_triggers WHERE table_owner = :owner AND table_name = :name ORDER BY trigger_name""", b);
            mvLog = query(c, """
                    SELECT log_table, primary_key, rowids, sequence, include_new_values
                      FROM dba_mview_logs WHERE log_owner = :owner AND master = :name""", b);
        }

        for (Map<String, Object> c : columns) {
            Object dv = c.get("default_value");
            c.put("default_value", dv instanceof String s ? s.strip() : dv);
            c.put("nullable", "Y".equals(c.get("nullable")));
            c.put("identity", "YES".equals(c.remove("identity_column")));
            c.put("default_on_null", "YES".equals(c.get("default_on_null")));
            c.put("invisible", "YES".equals(c.remove("hidden_column")));
            c.put("virtual_expression", "YES".equals(c.remove("virtual_column")) ? c.get("default_value") : null);
            if (c.get("virtual_expression") != null || Boolean.TRUE.equals(c.get("identity"))) c.put("default_value", null);
            c.put("char_semantics", "C".equals(c.get("char_used")));
            String dt = (String) c.get("data_type");
            c.put("length", Set.of("VARCHAR2", "NVARCHAR2", "CHAR", "NCHAR").contains(dt) ? c.get("char_length")
                    : "RAW".equals(dt) ? c.get("data_length") : null);
            if (dt.startsWith("TIMESTAMP")) {
                Matcher m = Pattern.compile("TIMESTAMP\\((\\d)\\)(.*)").matcher(dt);
                if (m.matches()) {  // el diccionario guarda TIMESTAMP(6) WITH TIME ZONE
                    c.put("data_type", "TIMESTAMP" + m.group(2));
                    c.put("precision", Long.parseLong(m.group(1)));
                }
            }
            Object mode = c.remove("generation_type");
            String opts = c.remove("identity_options") instanceof String s ? s : "";
            if (Boolean.TRUE.equals(c.get("identity"))) {
                Matcher st = Pattern.compile("START WITH: (-?\\d+)").matcher(opts);
                Matcher inc = Pattern.compile("INCREMENT BY: (-?\\d+)").matcher(opts);
                c.put("identity_mode", "ALWAYS".equals(mode) ? "ALWAYS" : Boolean.TRUE.equals(c.get("default_on_null")) ? "BY_DEFAULT_ON_NULL" : "BY_DEFAULT");
                c.put("identity_start", st.find() ? Long.parseLong(st.group(1)) : null);
                c.put("identity_increment", inc.find() ? Long.parseLong(inc.group(1)) : null);
                c.put("default_on_null", false);
            }
            c.put("type_display", typeDisplay(c));
        }
        // Los NOT NULL de columna son CHECK generados por el sistema: no se listan como restricciones
        constraints.removeIf(con -> "C".equals(con.get("type")) && "GENERATED NAME".equals(con.get("generated"))
                && String.valueOf(con.get("condition")).matches("(?is)\\s*\"[^\"]+\"\\s+IS\\s+NOT\\s+NULL\\s*"));
        for (Map<String, Object> con : constraints) {
            con.put("deferrable", "DEFERRABLE".equals(con.get("deferrable")));
            con.put("initially_deferred", "DEFERRED".equals(con.remove("deferred")));
            con.put("validated", "VALIDATED".equals(con.get("validated")));
            con.put("enabled", "ENABLED".equals(con.get("status")));
        }
        Map<String, List<Map<String, Object>>> colsByIndex = new LinkedHashMap<>();
        for (Map<String, Object> ic : indCols) {
            String expr = ic.get("column_expression") instanceof String s && !s.isBlank() ? s.strip() : (String) ic.get("column_name");
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("expression", expr);
            e.put("descending", "DESC".equals(ic.get("descend")));
            colsByIndex.computeIfAbsent((String) ic.get("index_name"), x -> new ArrayList<>()).add(e);
        }
        for (Map<String, Object> i : indexes) {
            List<Map<String, Object>> list = colsByIndex.getOrDefault((String) i.get("name"), new ArrayList<>());
            i.put("column_list", list);
            // DESC se implementa como índice por función: la expresión es la columna entre comillas
            for (Map<String, Object> col : list) {
                String ex = (String) col.get("expression");
                if (ex.matches("\"[A-Z][A-Z0-9_$#]*\"")) col.put("expression", ex.substring(1, ex.length() - 1));
            }
            i.put("columns", list.stream().map(x -> x.get("expression") + (Boolean.TRUE.equals(x.get("descending")) ? " DESC" : ""))
                    .collect(Collectors.joining(", ")));
            String t = (String) i.get("type");
            i.put("kind", "DOMAIN".equals(t) ? "DOMAIN" : t.contains("BITMAP") ? "BITMAP" : "UNIQUE".equals(i.get("uniqueness")) ? "UNIQUE" : "NORMAL");
            i.put("function_based", t.contains("FUNCTION-BASED") && !list.stream().allMatch(x -> ((String) x.get("expression")).matches("[A-Z][A-Z0-9_$#]*")));
            i.put("reverse", t.endsWith("/REV"));
            Object itypName = i.remove("ityp_name");
            Object itypOwner = i.remove("ityp_owner");
            i.put("indextype", itypName != null ? itypOwner + "." + itypName : null);
        }
        Map<String, Map<String, Object>> grouped = new LinkedHashMap<>();
        for (Map<String, Object> g : grants) {
            Map<String, Object> e = grouped.computeIfAbsent((String) g.get("grantee"), x -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("grantee", x);
                m.put("privileges", new ArrayList<String>());
                m.put("grantable", new ArrayList<String>());
                m.put("column_privileges", new ArrayList<String>());
                return m;
            });
            if (g.get("column_name") != null) {
                ((List<String>) e.get("column_privileges")).add(g.get("privilege") + "(" + g.get("column_name") + ")");
                continue;
            }
            ((List<String>) e.get("privileges")).add((String) g.get("privilege"));
            if ("YES".equals(g.get("grantable"))) ((List<String>) e.get("grantable")).add((String) g.get("privilege"));
        }
        Map<String, Object> props = obj.get(0);
        if (!part.isEmpty()) {  // en tablas particionadas estas propiedades viven en los valores por defecto de las particiones
            Map<String, Object> pp = part.get(0);
            props.putIfAbsent("tablespace_name", null);
            if (props.get("tablespace_name") == null) props.put("tablespace_name", pp.get("def_tablespace_name"));
            if (props.get("compression") == null) props.put("compression", pp.get("def_compression"));
            if (props.get("compress_for") == null) props.put("compress_for", pp.get("def_compress_for"));
            if (props.get("logging") == null) props.put("logging", pp.get("def_logging"));
            pp.keySet().removeAll(Set.of("def_tablespace_name", "def_compression", "def_compress_for", "def_logging"));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schema", owner);
        out.put("name", table);
        out.putAll(props);
        out.put("columns", columns);
        out.put("constraints", constraints);
        out.put("referenced_by", referencedBy);
        out.put("indexes", indexes);
        out.put("grants", new ArrayList<>(grouped.values()));
        out.put("synonyms", synonyms);
        out.put("partitioning", part.isEmpty() ? null : part.get(0));
        out.put("partitions", partitions);
        out.put("triggers", triggers);
        out.put("mv_log", mvLog.isEmpty() ? null : mvLog.get(0));
        return out;
    }

    public static Map<String, Object> describeUser(ConnInfo info, String owner) throws SQLException {
        try (Connection c = connect(info, "describe_user")) {
            checkSchema(c, info, owner);
            List<Map<String, Object>> account = query(c, """
                    SELECT username, account_status, created, default_tablespace, temporary_tablespace, profile,
                           lock_date, expiry_date, authentication_type
                      FROM dba_users WHERE username = :u""", "u", owner);
            if (account.isEmpty()) throw new java.util.NoSuchElementException("No existe el usuario " + owner);
            Map<String, Object> out = new LinkedHashMap<>(account.get(0));
            out.put("system_privileges", query(c, "SELECT privilege, admin_option FROM dba_sys_privs WHERE grantee = :u ORDER BY 1", "u", owner));
            out.put("roles", query(c, "SELECT granted_role AS role, admin_option, default_role FROM dba_role_privs WHERE grantee = :u ORDER BY 1", "u", owner));
            out.put("quotas", query(c, "SELECT tablespace_name, bytes, max_bytes FROM dba_ts_quotas WHERE username = :u", "u", owner));
            out.put("object_privileges", query(c, """
                    SELECT p.owner, p.table_name AS object_name, o.object_type,
                           LISTAGG(p.privilege, ', ') WITHIN GROUP (ORDER BY p.privilege) AS privileges,
                           MAX(p.grantable) AS grantable
                      FROM dba_tab_privs p
                      LEFT JOIN dba_objects o ON o.owner = p.owner AND o.object_name = p.table_name
                                             AND o.object_type NOT IN ('PACKAGE BODY','TYPE BODY','INDEX')
                     WHERE p.grantee = :u
                     GROUP BY p.owner, p.table_name, o.object_type
                     ORDER BY p.owner, p.table_name""", "u", owner));
            out.put("synonyms", query(c, """
                    SELECT synonym_name AS name, table_owner AS target_schema, table_name AS target_object
                      FROM dba_synonyms WHERE owner = :u ORDER BY 1""", "u", owner));
            return out;
        }
    }

    public static String getDdl(ConnInfo info, String schema, String objectType, String name) throws SQLException {
        String otype = objectType.toUpperCase();
        if (!OBJECT_TYPES.contains(otype) && !otype.equals("PACKAGE BODY"))
            throw new IllegalArgumentException("Tipo de objeto no soportado: " + objectType);
        String mdType = DDL_TYPE.getOrDefault(otype, otype);
        try (Connection c = connect(info, "get_ddl"); Statement st = c.createStatement()) {
            checkSchema(c, info, schema);
            st.execute("""
                    BEGIN
                      DBMS_METADATA.SET_TRANSFORM_PARAM(DBMS_METADATA.SESSION_TRANSFORM, 'STORAGE', FALSE);
                      DBMS_METADATA.SET_TRANSFORM_PARAM(DBMS_METADATA.SESSION_TRANSFORM, 'SEGMENT_ATTRIBUTES', FALSE);
                      DBMS_METADATA.SET_TRANSFORM_PARAM(DBMS_METADATA.SESSION_TRANSFORM, 'SQLTERMINATOR', TRUE);
                      DBMS_METADATA.SET_TRANSFORM_PARAM(DBMS_METADATA.SESSION_TRANSFORM, 'PRETTY', TRUE);
                    END;""");
            List<String> parts = new ArrayList<>();
            parts.add(((String) query(c, "SELECT DBMS_METADATA.GET_DDL(:t, :n, :o) AS d FROM dual", "t", mdType, "n", name, "o", schema)
                    .get(0).get("d")).strip());
            if (Set.of("TABLE", "VIEW", "MATERIALIZED VIEW").contains(otype)) {
                for (String dep : List.of("COMMENT", "INDEX", "OBJECT_GRANT")) {
                    if (!otype.equals("TABLE") && dep.equals("INDEX")) continue;
                    try {
                        parts.add(((String) query(c, "SELECT DBMS_METADATA.GET_DEPENDENT_DDL(:t, :n, :o) AS d FROM dual",
                                "t", dep, "n", name, "o", schema).get(0).get("d")).strip());
                    } catch (DbError e) {
                        // ORA-31608: no hay objetos dependientes de ese tipo
                    }
                }
            }
            return String.join("\n\n", parts);
        }
    }

    public static List<Map<String, Object>> search(ConnInfo info, String text) throws SQLException {
        List<Object> binds = new ArrayList<>(List.of("p", "%" + text.toUpperCase() + "%"));
        String notIn = (String) notIn(excluded(info), binds)[0];
        String sql = """
                SELECT * FROM (
                  SELECT 'OBJECT' AS match_on, o.owner AS schema, o.object_type, o.object_name,
                         NULL AS column_name, tc.comments
                    FROM dba_objects o
                    JOIN dba_users u ON u.username = o.owner AND u.oracle_maintained = 'N'
                    LEFT JOIN dba_tab_comments tc ON tc.owner = o.owner AND tc.table_name = o.object_name
                   WHERE o.object_type IN ('TABLE','VIEW','MATERIALIZED VIEW','PROCEDURE','FUNCTION','PACKAGE','SEQUENCE')
                     AND o.owner NOT IN (%1$s) AND o.generated = 'N' %2$s
                     AND (o.object_name LIKE :p OR UPPER(tc.comments) LIKE :p)
                  UNION ALL
                  SELECT 'COLUMN', c.owner, 'TABLE', c.table_name, c.column_name, cc.comments
                    FROM dba_tab_columns c
                    JOIN dba_users u ON u.username = c.owner AND u.oracle_maintained = 'N'
                    LEFT JOIN dba_col_comments cc ON cc.owner = c.owner AND cc.table_name = c.table_name
                                                 AND cc.column_name = c.column_name
                   WHERE c.owner NOT IN (%1$s) AND c.table_name NOT LIKE 'BIN$%%'
                     AND c.table_name NOT LIKE 'MLOG$%%' AND c.table_name NOT LIKE 'RUPD$%%'
                     AND (c.column_name LIKE :p OR UPPER(cc.comments) LIKE :p)
                ) ORDER BY schema, object_name, column_name FETCH FIRST 300 ROWS ONLY""".formatted(notIn, INTERNAL_FILTER);
        try (Connection c = connect(info, "search")) {
            return query(c, sql, binds.toArray());
        }
    }

    // ------------------------------------------------------------------ validación / importación
    public static List<Finding> preflight(ConnInfo info, List<Op> ops) throws SQLException {
        try (Connection c = connect(info, "preflight")) {
            return new Preflight((sql, binds) -> query(c, sql, binds)).run(ops);
        }
    }

    public static List<SqlImport.Imported> importScript(ConnInfo info, String sql, String defaultSchema) {
        Map<String, Map<String, Object>> tables = new LinkedHashMap<>();
        return SqlImport.importScript(sql, defaultSchema, (schema, table, column) -> {
            String key = schema + "." + table;
            if (!tables.containsKey(key)) {
                try {
                    tables.put(key, describeTable(info, schema, table));
                } catch (java.util.NoSuchElementException | SQLException | SecurityException e) {
                    tables.put(key, null);
                }
            }
            Map<String, Object> t = tables.get(key);
            if (t == null) return null;
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> cols = (List<Map<String, Object>>) t.get("columns");
            return cols.stream().filter(x -> column.equals(x.get("name"))).findFirst().map(OracleEngine::catalogColumnToOp).orElse(null);
        });
    }

    /** Columna de describeTable -> definición de columna de operación. */
    public static Map<String, Object> catalogColumnToOp(Map<String, Object> c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", c.get("name"));
        m.put("data_type", c.get("data_type"));
        m.put("length", c.get("length"));
        m.put("char_semantics", c.get("char_semantics"));
        m.put("precision", c.get("precision"));
        m.put("scale", c.get("scale"));
        m.put("nullable", c.get("nullable"));
        m.put("default", c.get("default_value"));
        m.put("default_on_null", c.get("default_on_null"));
        m.put("comment", c.get("comments"));
        m.put("identity", c.get("identity"));
        m.put("identity_mode", c.getOrDefault("identity_mode", "BY_DEFAULT_ON_NULL"));
        m.put("identity_start", c.get("identity_start"));
        m.put("identity_increment", c.get("identity_increment"));
        m.put("virtual_expression", c.get("virtual_expression"));
        m.put("invisible", c.get("invisible"));
        return m;
    }

    // ------------------------------------------------------------------ ejecución
    /** Resultado de una sentencia ejecutada. */
    public record Result(String sql, boolean ok, String message, String secret) {}

    /** Ejecuta en orden y se detiene en el primer error. El DDL en Oracle hace commit implícito. */
    public static List<Result> execute(ConnInfo info, List<Stmt> statements, String tag) throws SQLException {
        List<Result> results = new ArrayList<>();
        try (Connection c = connect(info, "exec " + tag, 15 * 60); Statement st = c.createStatement()) {
            st.setQueryTimeout(15 * 60);
            st.setEscapeProcessing(false);
            for (Stmt s : statements) {
                String sql = s.sql(), secret = null;
                if (s.secretPlaceholder() != null) {
                    secret = Crypto.randomPassword();
                    sql = sql.replace(s.secretPlaceholder(), secret);
                }
                try {
                    st.clearWarnings();
                    st.execute(sql);
                    SQLWarning w = st.getWarnings();
                    if (w != null && s.plsql()) {
                        results.add(new Result(s.sql(), false, "Compilado con errores: " + w.getMessage() + "\n" + plsqlErrors(c, s.sql()), null));
                        break;
                    }
                    c.commit();
                    results.add(new Result(s.sql(), true, "OK", secret));
                } catch (SQLException e) {
                    try {
                        c.rollback();
                    } catch (SQLException ignored) {
                        // la conexión puede estar caída; el error original es el que importa
                    }
                    results.add(new Result(s.sql(), false, e.getMessage().strip(), null));
                    break;
                }
            }
        }
        return results;
    }

    private static String plsqlErrors(Connection c, String sql) {
        Matcher m = Pattern.compile("(PACKAGE\\s+BODY|PACKAGE|PROCEDURE|FUNCTION|TRIGGER|TYPE|VIEW)\\s+\"?(\\w+)\"?\\.\"?(\\w+)\"?",
                Pattern.CASE_INSENSITIVE).matcher(sql);
        if (!m.find()) return "";
        try {
            return query(c, "SELECT line, position, text FROM dba_errors WHERE owner = :o AND name = :n AND type = :t ORDER BY sequence",
                    "o", m.group(2).toUpperCase(), "n", m.group(3).toUpperCase(), "t", m.group(1).toUpperCase().replaceAll("\\s+", " "))
                    .stream().map(r -> "  línea " + r.get("line") + ":" + r.get("position") + " " + r.get("text")).collect(Collectors.joining("\n"));
        } catch (DbError e) {
            return "  (detalle no disponible: la cuenta ejecutora no tiene lectura de DBA_ERRORS)";
        }
    }

    static String typeDisplay(Map<String, Object> c) {
        String dt = (String) c.get("data_type");
        Object precision = c.get("precision"), scale = c.get("scale");
        if (Set.of("VARCHAR2", "NVARCHAR2", "CHAR", "NCHAR").contains(dt)) {
            String unit = "C".equals(c.get("char_used")) && (dt.equals("VARCHAR2") || dt.equals("CHAR")) ? " CHAR" : "";
            return dt + "(" + c.get("char_length") + unit + ")";
        }
        if (dt.startsWith("TIMESTAMP") && precision != null) return dt.replaceFirst("TIMESTAMP", "TIMESTAMP(" + precision + ")");
        if (dt.equals("RAW")) return "RAW(" + c.get("data_length") + ")";
        if (dt.equals("NUMBER") && precision != null) {
            return scale != null && ((Number) scale).longValue() != 0 ? "NUMBER(" + precision + "," + scale + ")" : "NUMBER(" + precision + ")";
        }
        if (dt.equals("NUMBER") && scale != null && ((Number) scale).longValue() == 0) return "INTEGER";
        return dt;
    }

    public static String readClob(Reader r) throws java.io.IOException {
        StringBuilder sb = new StringBuilder();
        char[] buf = new char[8192];
        int n;
        while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
        return sb.toString();
    }
}
