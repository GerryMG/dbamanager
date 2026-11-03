package dbamanager.service;

import dbamanager.core.ApiException;
import dbamanager.core.Crypto;
import dbamanager.core.Json;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Respaldo y migración de la metadata en JSON portable y firmado (HMAC con SECRETS_KEY). Las
 * contraseñas de conexiones viajan cifradas: el destino necesita la misma SECRETS_KEY. Las firmas de
 * los DBAs viajan tal cual y se pueden volver a verificar en el destino.
 */
@ApplicationScoped
public class Backup {
    public static final String FORMAT = "dbamanager-metadata";
    public static final int VERSION = 2;
    /** En orden de dependencias (llaves foráneas). */
    static final List<String> TABLES = List.of("app_users", "user_settings", "db_connections", "projects", "dba_keys",
            "approver_scopes", "change_requests", "request_operations", "approvals", "request_comments", "executions",
            "audit_log", "root_resets");
    private static final int TSTZ_ORACLE = -101;

    @Inject AgroalDataSource ds;
    @Inject Crypto crypto;

    public Map<String, Object> export() throws SQLException {
        Map<String, Object> tables = new LinkedHashMap<>();
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            for (String t : TABLES) {
                List<Map<String, Object>> rows = new ArrayList<>();
                try (ResultSet rs = st.executeQuery("SELECT * FROM " + t + " ORDER BY 1")) {
                    ResultSetMetaData md = rs.getMetaData();
                    while (rs.next()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (int i = 1; i <= md.getColumnCount(); i++) row.put(md.getColumnName(i).toLowerCase(), read(rs, i, md.getColumnType(i)));
                        rows.add(row);
                    }
                }
                tables.put(t, rows);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("format", FORMAT);
        out.put("version", VERSION);
        out.put("exported_at", OffsetDateTime.now().toString());
        out.put("tables", tables);
        out.put("signature", sign(out));
        return out;
    }

    private static Object read(ResultSet rs, int i, int type) throws SQLException {
        if (type == Types.TIMESTAMP_WITH_TIMEZONE || type == TSTZ_ORACLE) {
            OffsetDateTime t = rs.getObject(i, OffsetDateTime.class);
            return t == null ? null : t.toString();
        }
        if (type == Types.CLOB || type == Types.NCLOB) return rs.getString(i);
        if (type == Types.NUMERIC || type == Types.DECIMAL) {
            java.math.BigDecimal d = rs.getBigDecimal(i);
            return d == null ? null : d.longValueExact();
        }
        return rs.getObject(i);
    }

    /** Un respaldo editado a mano (p. ej. con aprobaciones inventadas) no se puede restaurar. */
    String sign(Map<String, Object> data) {
        Map<String, Object> body = new LinkedHashMap<>(data);
        body.remove("signature");
        return crypto.seal("backup:" + Json.canonical(body));
    }

    public boolean isEmpty(Connection c) throws SQLException {
        try (Statement st = c.createStatement()) {
            for (String t : TABLES) {
                try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + t)) {
                    rs.next();
                    if (rs.getLong(1) > 0) return false;
                }
            }
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> restore(Map<String, Object> data, boolean replace) throws SQLException {
        if (!FORMAT.equals(data.get("format"))) throw ApiException.of(422, "El archivo no es un respaldo de DBA Manager");
        if (!Integer.valueOf(VERSION).equals(data.get("version")))
            throw ApiException.of(422, "Versión de respaldo no soportada: " + data.get("version"));
        if (!(data.get("signature") instanceof String sig) || !Crypto.sameHex(sig, sign(data)))
            throw ApiException.of(422, "Firma del respaldo inválida: el archivo fue modificado o se generó con otra SECRETS_KEY");
        Map<String, Object> tables = (Map<String, Object>) data.get("tables");
        Map<String, Object> counts = new LinkedHashMap<>();
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            try {
                if (!isEmpty(c)) {
                    if (!replace) throw ApiException.of(422, "La metadata destino no está vacía; solo se restaura sobre una metadata vacía");
                    try (Statement st = c.createStatement()) {
                        st.executeUpdate("UPDATE dba_keys SET endorsed_by = NULL");
                        for (String t : TABLES.reversed()) st.executeUpdate("DELETE FROM " + t);
                    }
                }
                for (String t : TABLES) {
                    List<Map<String, Object>> rows = (List<Map<String, Object>>) tables.getOrDefault(t, List.of());
                    insertAll(c, t, rows);
                    counts.put(t, rows.size());
                }
                try (Statement st = c.createStatement()) {
                    // Un respaldo no puede activar la ejecución a nadie: cada DBA la vuelve a activar con su llave.
                    st.executeUpdate("UPDATE user_settings SET can_execute = 0, can_execute_until = NULL");
                    // Lo aprobado y sin ejecutar debe volver a aprobarse en esta instalación.
                    st.executeUpdate("UPDATE change_requests SET status = 'PENDING_APPROVAL' "
                            + "WHERE status IN ('APPROVED','FAILED','EXECUTING','PENDING_APPROVAL')");
                    st.executeUpdate("UPDATE approvals SET active = 0 WHERE request_id IN "
                            + "(SELECT id FROM change_requests WHERE status = 'PENDING_APPROVAL')");
                }
                c.commit();
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
            // Ajusta las identidades para que los nuevos IDs no choquen con los importados (DDL: commit implícito)
            try (Statement st = c.createStatement()) {
                for (String t : TABLES) {
                    if (!t.equals("app_users") && !t.equals("user_settings"))
                        st.execute("ALTER TABLE " + t + " MODIFY id GENERATED BY DEFAULT AS IDENTITY (START WITH LIMIT VALUE)");
                }
            }
        }
        return counts;
    }

    private static void insertAll(Connection c, String table, List<Map<String, Object>> rows) throws SQLException {
        if (rows.isEmpty()) return;
        Map<String, Integer> types = new LinkedHashMap<>();
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT * FROM " + table + " WHERE 1 = 0")) {
            ResultSetMetaData md = rs.getMetaData();
            for (int i = 1; i <= md.getColumnCount(); i++) types.put(md.getColumnName(i).toLowerCase(), md.getColumnType(i));
        }
        List<String> cols = new ArrayList<>(types.keySet());
        String sql = "INSERT INTO " + table + " (" + String.join(", ", cols) + ") VALUES (" + "?, ".repeat(cols.size() - 1) + "?)";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (Map<String, Object> row : rows) {
                for (int i = 0; i < cols.size(); i++) {
                    Object v = row.get(cols.get(i));
                    int type = types.get(cols.get(i));
                    if (v == null) ps.setNull(i + 1, type == TSTZ_ORACLE ? Types.TIMESTAMP_WITH_TIMEZONE : type);
                    else if (type == Types.TIMESTAMP_WITH_TIMEZONE || type == TSTZ_ORACLE) ps.setObject(i + 1, OffsetDateTime.parse(v.toString()));
                    else if (type == Types.CLOB || type == Types.NCLOB) ps.setString(i + 1, v.toString());
                    else if (v instanceof Boolean b) ps.setInt(i + 1, b ? 1 : 0);
                    else ps.setObject(i + 1, v);
                }
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }
}
