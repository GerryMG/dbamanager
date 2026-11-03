package dbamanager.engine;

import dbamanager.core.Json;
import dbamanager.ops.OpParser;
import dbamanager.ops.Ops;
import dbamanager.ops.Ops.*;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Traducción de operaciones a SQL de Oracle. Los identificadores ya llegan validados, por eso se
 * emiten sin comillas; los textos libres (comentarios) se escapan.
 */
public final class OracleDdl {
    public static final String PASSWORD_PLACEHOLDER = "{{PASSWORD}}";
    public static final int[] MIN_VERSION = {12, 1};

    private OracleDdl() {}

    /** Tipos de dato soportados. "min_version": versión mínima de Oracle que tiene el tipo. */
    public static final List<Map<String, Object>> DATA_TYPES = List.of(
            type("VARCHAR2", true, 4000, 100, false, false, null),
            type("NVARCHAR2", true, 2000, 100, false, false, null),
            type("CHAR", true, 2000, 1, false, false, null),
            type("NCHAR", true, 1000, 1, false, false, null),
            type("NUMBER", false, 0, 0, true, true, null),
            type("INTEGER", false, 0, 0, false, false, null),
            type("FLOAT", false, 0, 0, true, false, null),
            type("BINARY_FLOAT", false, 0, 0, false, false, null),
            type("BINARY_DOUBLE", false, 0, 0, false, false, null),
            type("DATE", false, 0, 0, false, false, null),
            type("TIMESTAMP", false, 0, 0, true, false, null),
            type("TIMESTAMP WITH TIME ZONE", false, 0, 0, true, false, null),
            type("TIMESTAMP WITH LOCAL TIME ZONE", false, 0, 0, true, false, null),
            type("CLOB", false, 0, 0, false, false, null),
            type("NCLOB", false, 0, 0, false, false, null),
            type("BLOB", false, 0, 0, false, false, null),
            type("RAW", true, 2000, 16, false, false, null),
            type("JSON", false, 0, 0, false, false, "21"),
            type("BOOLEAN", false, 0, 0, false, false, "23"));
    private static final Map<String, Map<String, Object>> TYPES = DATA_TYPES.stream()
            .collect(Collectors.toMap(t -> (String) t.get("name"), t -> t));

    private static Map<String, Object> type(String name, boolean length, int max, int def, boolean precision, boolean scale,
                                            String minVersion) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        if (length) {
            m.put("length", true);
            m.put("max_length", max);
            m.put("default_length", def);
        }
        if (precision) m.put("precision", true);
        if (scale) m.put("scale", true);
        if (minVersion != null) m.put("min_version", minVersion);
        return m;
    }

    // ------------------------------------------------------------------ versiones
    public static int[] parseVersion(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            String[] p = text.split("\\.");
            int[] v = new int[Math.min(4, p.length)];
            for (int i = 0; i < v.length; i++) v[i] = Integer.parseInt(p[i].trim());
            return v;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** a < b comparando por componentes (los faltantes cuentan como menores). */
    public static boolean lt(int[] a, int... b) {
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            if (i >= a.length) return true;
            if (i >= b.length) return false;
            if (a[i] != b[i]) return a[i] < b[i];
        }
        return false;
    }

    static String vtxt(int[] v) {
        return java.util.Arrays.stream(v).mapToObj(String::valueOf).collect(Collectors.joining("."));
    }

    private static final Set<String> IDENT_KEYS = Set.of("schema", "table", "name", "column_name", "index_name",
            "constraint_name", "synonym", "object", "grantee", "target_schema", "target_object", "old_name", "new_name",
            "primary_key_name", "ref_schema", "ref_table", "partition_name", "tablespace", "index_tablespace",
            "primary_key_tablespace", "default_tablespace");
    private static final Set<String> IDENT_LIST_KEYS = Set.of("columns", "primary_key", "ref_columns", "roles");

    @SuppressWarnings("unchecked")
    private static void walk(Object data, List<String> out) {
        if (data instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> en : m.entrySet()) {
                String k = String.valueOf(en.getKey());
                Object v = en.getValue();
                if (IDENT_KEYS.contains(k) && v instanceof String s) out.add(s);
                else if (IDENT_LIST_KEYS.contains(k) && v instanceof List<?> l) {
                    for (Object x : l) if (x instanceof String s) out.add(s); else walk(x, out);
                } else walk(v, out);
            }
        } else if (data instanceof List<?> l) {
            for (Object x : l) walk(x, out);
        }
    }

    /** Rechaza sintaxis que la versión del servidor no soporta. Sin versión conocida no se restringe. */
    public static void checkVersion(Op op, int[] version) {
        if (version == null) return;
        if (lt(version, MIN_VERSION))
            throw new IllegalArgumentException("DBA Manager requiere Oracle " + vtxt(MIN_VERSION) + " o superior (servidor: " + vtxt(version) + ")");
        List<String> errors = new ArrayList<>();
        if (lt(version, 12, 2)) {
            List<String> names = new ArrayList<>();
            walk(OpParser.toMap(op), names);
            Set<String> longNames = new TreeSet<>();
            for (String n : names) if (n.getBytes(StandardCharsets.UTF_8).length > 30 && !n.equals("PUBLIC")) longNames.add(n);
            if (!longNames.isEmpty())
                errors.add("Oracle " + vtxt(version) + " admite nombres de máximo 30 bytes: " + String.join(", ", longNames));
        }
        if (op instanceof CreateTable ct) for (Column c : ct.columns) errors.addAll(columnVersionErrors(c, version));
        if (op instanceof AddColumn ac && ac.column != null) errors.addAll(columnVersionErrors(ac.column, version));
        if (op instanceof ModifyColumn mc && mc.column != null) errors.addAll(columnVersionErrors(mc.column, version));
        if (op instanceof AlterSequence as && as.restartWith != null && lt(version, 18))
            errors.add("ALTER SEQUENCE ... RESTART requiere Oracle 18c; en 12c hay que eliminar y recrear la secuencia");
        if (op instanceof Grant g && g.privileges.contains("READ") && lt(version, 12, 1, 0, 2))
            errors.add("El privilegio READ requiere Oracle 12.1.0.2; usa SELECT");
        if (op instanceof CreateTable ct && ct.partitioning != null && "LIST".equals(ct.partitioning.method)
                && ct.partitioning.columns.size() > 1 && lt(version, 12, 2))
            errors.add("El particionado LIST por varias columnas requiere Oracle 12.2");
        if (op instanceof CreateIndex ci && "ADVANCED_HIGH".equals(ci.compression) && lt(version, 12, 1, 0, 2))
            errors.add("COMPRESS ADVANCED HIGH requiere Oracle 12.1.0.2");
        if (!errors.isEmpty()) throw new IllegalArgumentException(String.join("; ", errors));
    }

    private static List<String> columnVersionErrors(Column c, int[] version) {
        List<String> errors = new ArrayList<>();
        if ("BOOLEAN".equals(c.dataType) && lt(version, 23))
            errors.add(c.name + ": el tipo BOOLEAN requiere Oracle 23ai (usa NUMBER(1) o CHAR(1) con CHECK)");
        if ("JSON".equals(c.dataType) && lt(version, 21))
            errors.add(c.name + ": el tipo JSON requiere Oracle 21c (usa CLOB o VARCHAR2 con CHECK (" + c.name + " IS JSON))");
        return errors;
    }

    // ------------------------------------------------------------------ piezas
    public static String q(String text) {
        return "'" + text.replace("'", "''") + "'";
    }

    public static String renderType(Column col) {
        if (col.dataType == null) return "";  // columna virtual: Oracle deduce el tipo
        Map<String, Object> spec = TYPES.get(col.dataType);
        if (spec == null) throw new IllegalArgumentException("Tipo de dato no soportado en Oracle: " + col.dataType);
        String name = col.dataType;
        if (Boolean.TRUE.equals(spec.get("length"))) {
            int max = (int) spec.get("max_length");
            if (col.length == null) throw new IllegalArgumentException(col.name + ": " + name + " requiere longitud");
            if (col.length > max) throw new IllegalArgumentException(col.name + ": longitud máxima de " + name + " es " + max);
            String unit = col.charSemantics && (name.equals("VARCHAR2") || name.equals("CHAR")) ? " CHAR" : "";
            return name + "(" + col.length + unit + ")";
        }
        if (name.equals("NUMBER") && col.precision != null && col.precision != 0) {
            if (col.precision < 1 || col.precision > 38) throw new IllegalArgumentException(col.name + ": la precisión de NUMBER va de 1 a 38");
            return col.scale != null ? "NUMBER(" + col.precision + "," + col.scale + ")" : "NUMBER(" + col.precision + ")";
        }
        if (name.equals("NUMBER") && col.scale != null) return "NUMBER(*," + col.scale + ")";
        if (name.equals("FLOAT") && col.precision != null && col.precision != 0) {
            if (col.precision < 1 || col.precision > 126) throw new IllegalArgumentException(col.name + ": la precisión de FLOAT va de 1 a 126");
            return "FLOAT(" + col.precision + ")";
        }
        if (name.startsWith("TIMESTAMP") && col.precision != null) {
            if (col.precision < 0 || col.precision > 9) throw new IllegalArgumentException(col.name + ": la precisión de TIMESTAMP va de 0 a 9");
            return name.replaceFirst("TIMESTAMP", "TIMESTAMP(" + col.precision + ")");
        }
        return name;
    }

    private static final Map<String, String> IDENTITY = Map.of(
            "ALWAYS", "GENERATED ALWAYS AS IDENTITY",
            "BY_DEFAULT", "GENERATED BY DEFAULT AS IDENTITY",
            "BY_DEFAULT_ON_NULL", "GENERATED BY DEFAULT ON NULL AS IDENTITY");

    public static String renderColumn(Column col) {
        List<String> parts = new ArrayList<>();
        parts.add(col.name);
        String t = renderType(col);
        if (!t.isEmpty()) parts.add(t);
        if (col.invisible) parts.add("INVISIBLE");
        if (col.virtualExpression != null) {
            parts.add("GENERATED ALWAYS AS (" + col.virtualExpression + ") VIRTUAL");
        } else if (col.identity) {
            if (!"NUMBER".equals(col.dataType) && !"INTEGER".equals(col.dataType))
                throw new IllegalArgumentException(col.name + ": solo columnas numéricas pueden ser identidad");
            List<String> opts = new ArrayList<>();
            if (col.identityStart != null) opts.add("START WITH " + col.identityStart);
            if (col.identityIncrement != null) opts.add("INCREMENT BY " + col.identityIncrement);
            parts.add(IDENTITY.get(col.identityMode) + (opts.isEmpty() ? "" : " (" + String.join(" ", opts) + ")"));
        } else if (col.defaultValue != null) {
            parts.add("DEFAULT " + (col.defaultOnNull ? "ON NULL " : "") + col.defaultValue);
        }
        if (!col.nullable) parts.add("NOT NULL");
        return String.join(" ", parts);
    }

    public static String renderConstraint(ConstraintDef c, String schema) {
        StringBuilder out = new StringBuilder(c.name != null ? "CONSTRAINT " + c.name + " " : "");
        String cols = String.join(", ", c.columns);
        switch (c.kind) {
            case "PRIMARY_KEY" -> out.append("PRIMARY KEY (").append(cols).append(")");
            case "UNIQUE" -> out.append("UNIQUE (").append(cols).append(")");
            case "FOREIGN_KEY" -> {
                out.append("FOREIGN KEY (").append(cols).append(") REFERENCES ")
                        .append(c.refSchema != null ? c.refSchema : schema).append(".").append(c.refTable);
                if (!c.refColumns.isEmpty()) out.append(" (").append(String.join(", ", c.refColumns)).append(")");
                if ("CASCADE".equals(c.onDelete)) out.append(" ON DELETE CASCADE");
                else if ("SET_NULL".equals(c.onDelete)) out.append(" ON DELETE SET NULL");
            }
            default -> out.append("CHECK (").append(c.condition).append(")");
        }
        if (c.indexTablespace != null && (c.kind.equals("PRIMARY_KEY") || c.kind.equals("UNIQUE")))
            out.append(" USING INDEX TABLESPACE ").append(c.indexTablespace);
        if (c.deferrable) out.append(" DEFERRABLE").append(c.initiallyDeferred ? " INITIALLY DEFERRED" : "");
        if (!c.enabled) out.append(" DISABLE");
        else if (!c.validated) out.append(" ENABLE NOVALIDATE");
        return out.toString();
    }

    private static List<Stmt> columnComment(String schema, String table, Column col) {
        if (col.comment != null && !col.comment.isEmpty())
            return List.of(new Stmt("COMMENT ON COLUMN " + schema + "." + table + "." + col.name + " IS " + q(col.comment)));
        return List.of();
    }

    private static final Map<String, String> COMPRESSION = Map.of(
            "NONE", "NOCOMPRESS", "BASIC", "ROW STORE COMPRESS BASIC", "ADVANCED", "ROW STORE COMPRESS ADVANCED");

    private static String partition(PartitionDef p, String method) {
        StringBuilder out = new StringBuilder("PARTITION " + p.name);
        if (method.equals("RANGE")) out.append(" VALUES LESS THAN (").append(p.values).append(")");
        else if (method.equals("LIST")) out.append(" VALUES (").append(p.values).append(")");
        if (p.tablespace != null) out.append(" TABLESPACE ").append(p.tablespace);
        return out.toString();
    }

    static String renderPartitioning(Partitioning pt) {
        String out = "PARTITION BY " + pt.method + " (" + String.join(", ", pt.columns) + ")";
        if (pt.interval != null) out += " INTERVAL (" + pt.interval + ")";
        if (pt.method.equals("HASH") && pt.partitions.isEmpty()) return out + " PARTITIONS " + pt.hashCount;
        return out + " (\n  " + pt.partitions.stream().map(p -> partition(p, pt.method)).collect(Collectors.joining(",\n  ")) + "\n)";
    }

    static List<Stmt> splitCustomSql(String sql) {
        List<Stmt> out = new ArrayList<>();
        for (String stmt : SqlImport.splitScript(sql)) {
            String text = stmt.strip();
            if (SqlImport.PLSQL_START.matcher(SqlImport.stripComments(text)).find())
                out.add(Stmt.plsql(text.replaceAll("/+$", "").strip()));
            else out.add(new Stmt(text.replaceAll(";+$", "").strip()));
        }
        return out;
    }

    // ------------------------------------------------------------------ render
    public static List<Stmt> render(Op op, int[] version) {
        checkVersion(op, version);
        String s = op.schema;
        String tbl = op instanceof TableOp t ? s + "." + t.table : null;

        if (op instanceof CreateTable o) {
            List<String> lines = new ArrayList<>();
            for (Column c : o.columns) lines.add(renderColumn(c));
            if (!o.primaryKey.isEmpty()) {
                String pk = "CONSTRAINT " + (o.primaryKeyName != null ? o.primaryKeyName : truncate("PK_" + o.table))
                        + " PRIMARY KEY (" + String.join(", ", o.primaryKey) + ")";
                if (o.primaryKeyTablespace != null) pk += " USING INDEX TABLESPACE " + o.primaryKeyTablespace;
                lines.add(pk);
            }
            for (ConstraintDef c : o.constraints) lines.add(renderConstraint(c, s));
            String kind = o.temporary != null ? "GLOBAL TEMPORARY TABLE" : "TABLE";
            StringBuilder sql = new StringBuilder("CREATE " + kind + " " + tbl + " (\n  " + String.join(",\n  ", lines) + "\n)");
            if (o.temporary != null) sql.append(" ON COMMIT ").append(o.temporary.equals("DELETE_ROWS") ? "DELETE" : "PRESERVE").append(" ROWS");
            if (o.tablespace != null) sql.append(" TABLESPACE ").append(o.tablespace);
            if (o.compression != null) sql.append(" ").append(COMPRESSION.get(o.compression));
            if (o.logging != null) sql.append(o.logging ? " LOGGING" : " NOLOGGING");
            if (o.partitioning != null) sql.append("\n").append(renderPartitioning(o.partitioning));
            List<Stmt> st = new ArrayList<>(List.of(new Stmt(sql.toString())));
            if (o.comment != null && !o.comment.isEmpty()) st.add(new Stmt("COMMENT ON TABLE " + tbl + " IS " + q(o.comment)));
            for (Column c : o.columns) st.addAll(columnComment(s, o.table, c));
            return st;
        }
        if (op instanceof AddColumn o) {
            List<Stmt> st = new ArrayList<>(List.of(new Stmt("ALTER TABLE " + tbl + " ADD (" + renderColumn(o.column) + ")")));
            st.addAll(columnComment(s, o.table, o.column));
            return st;
        }
        if (op instanceof ModifyColumn o) {
            Column c = o.column, prev = o.previous;
            if (prev != null && (c.virtualExpression != null || prev.virtualExpression != null)
                    && !Objects.equals(c.virtualExpression, prev.virtualExpression))
                throw new IllegalArgumentException(c.name + ": cambiar la expresión de una columna virtual requiere eliminarla y crearla de nuevo");
            List<String> parts = new ArrayList<>();
            if (!renderType(c).isEmpty() && (prev == null || !renderType(c).equals(renderType(prev)))) parts.add(renderType(c));
            if (c.virtualExpression == null && !c.identity && (prev == null || !Objects.equals(emptyNull(c.defaultValue), emptyNull(prev.defaultValue))
                    || c.defaultOnNull != prev.defaultOnNull))
                parts.add(c.defaultValue != null ? "DEFAULT " + (c.defaultOnNull ? "ON NULL " : "") + c.defaultValue : "DEFAULT NULL");
            if (prev == null || c.nullable != prev.nullable) parts.add(c.nullable ? "NULL" : "NOT NULL");
            List<Stmt> st = new ArrayList<>();
            if (!parts.isEmpty()) st.add(new Stmt("ALTER TABLE " + tbl + " MODIFY (" + c.name + " " + String.join(" ", parts) + ")"));
            if ((prev == null && c.invisible) || (prev != null && c.invisible != prev.invisible))
                // Oracle no permite combinar el cambio de visibilidad con otras modificaciones (ORA-54046)
                st.add(new Stmt("ALTER TABLE " + tbl + " MODIFY (" + c.name + " " + (c.invisible ? "INVISIBLE" : "VISIBLE") + ")"));
            if (c.comment != null && (prev == null || !c.comment.equals(prev.comment)))
                st.add(new Stmt("COMMENT ON COLUMN " + tbl + "." + c.name + " IS " + q(c.comment)));
            if (st.isEmpty()) throw new IllegalArgumentException("La columna " + c.name + " no tiene cambios respecto a su definición actual");
            return st;
        }
        if (op instanceof DropColumn o) return List.of(new Stmt("ALTER TABLE " + tbl + " DROP COLUMN " + o.columnName));
        if (op instanceof DropTable o) {
            return List.of(new Stmt("DROP TABLE " + tbl + (o.cascadeConstraints ? " CASCADE CONSTRAINTS" : "") + (o.purge ? " PURGE" : "")));
        }
        if (op instanceof AlterTableOptions o) {
            List<Stmt> st = new ArrayList<>();
            if (o.logging != null) st.add(new Stmt("ALTER TABLE " + tbl + " " + (o.logging ? "LOGGING" : "NOLOGGING")));
            if (o.compression != null) st.add(new Stmt("ALTER TABLE " + tbl + " " + COMPRESSION.get(o.compression)));
            if (o.readOnly != null) st.add(new Stmt("ALTER TABLE " + tbl + " " + (o.readOnly ? "READ ONLY" : "READ WRITE")));
            return st;
        }
        if (op instanceof Rename o) {
            return switch (o.target) {
                case "TABLE" -> List.of(new Stmt("ALTER TABLE " + s + "." + o.oldName + " RENAME TO " + o.newName));
                case "COLUMN" -> List.of(new Stmt("ALTER TABLE " + s + "." + o.table + " RENAME COLUMN " + o.oldName + " TO " + o.newName));
                case "CONSTRAINT" -> List.of(new Stmt("ALTER TABLE " + s + "." + o.table + " RENAME CONSTRAINT " + o.oldName + " TO " + o.newName));
                default -> List.of(new Stmt("ALTER INDEX " + s + "." + o.oldName + " RENAME TO " + o.newName));
            };
        }
        if (op instanceof SetComment o) {
            if (o.column != null) return List.of(new Stmt("COMMENT ON COLUMN " + tbl + "." + o.column + " IS " + q(o.comment)));
            return List.of(new Stmt("COMMENT ON TABLE " + tbl + " IS " + q(o.comment)));
        }
        if (op instanceof AddConstraint o) return List.of(new Stmt("ALTER TABLE " + tbl + " ADD " + renderConstraint(o.constraint, s)));
        if (op instanceof DropConstraint o) {
            return List.of(new Stmt("ALTER TABLE " + tbl + " DROP CONSTRAINT " + o.constraintName + (o.cascade ? " CASCADE" : "")
                    + (o.index != null ? " " + o.index + " INDEX" : "")));
        }
        if (op instanceof ConstraintState o) {
            String state = o.action.equals("DISABLE") ? "DISABLE" : (o.validateExisting ? "ENABLE" : "ENABLE NOVALIDATE");
            return List.of(new Stmt("ALTER TABLE " + tbl + " MODIFY CONSTRAINT " + o.constraintName + " " + state));
        }
        if (op instanceof CreateIndex o) {
            String kind = switch (o.indexType) {
                case "UNIQUE" -> "UNIQUE ";
                case "BITMAP" -> "BITMAP ";
                default -> "";
            };
            String cols = o.columns.stream().map(c -> c.expression + (c.descending ? " DESC" : "")).collect(Collectors.joining(", "));
            StringBuilder sql = new StringBuilder("CREATE " + kind + "INDEX " + s + "." + o.indexName + " ON " + tbl + " (" + cols + ")");
            if (o.indexType.equals("DOMAIN")) {
                sql.append(" INDEXTYPE IS ").append(o.indextype);
                if (o.parameters != null) sql.append(" PARAMETERS (").append(q(o.parameters)).append(")");
                return List.of(new Stmt(sql.toString()));
            }
            if (o.local) sql.append(" LOCAL");
            if (o.reverse) sql.append(" REVERSE");
            if (o.tablespace != null) sql.append(" TABLESPACE ").append(o.tablespace);
            if ("PREFIX".equals(o.compression)) sql.append(" COMPRESS").append(o.prefixLength != null ? " " + o.prefixLength : "");
            else if (o.compression != null) sql.append(" COMPRESS ADVANCED ").append(o.compression.split("_")[1]);
            if (o.invisible) sql.append(" INVISIBLE");
            if (o.online) sql.append(" ONLINE");
            return List.of(new Stmt(sql.toString()));
        }
        if (op instanceof DropIndex o) return List.of(new Stmt("DROP INDEX " + s + "." + o.indexName + (o.online ? " ONLINE" : "")));
        if (op instanceof AlterIndex o) {
            if (o.action.equals("REBUILD")) {
                return List.of(new Stmt("ALTER INDEX " + s + "." + o.indexName + " REBUILD"
                        + (o.tablespace != null ? " TABLESPACE " + o.tablespace : "") + (o.online ? " ONLINE" : "")));
            }
            return List.of(new Stmt("ALTER INDEX " + s + "." + o.indexName + " " + o.action));
        }
        if (op instanceof AddPartition o) return List.of(new Stmt("ALTER TABLE " + tbl + " ADD " + partition(o.partition, o.method)));
        if (op instanceof DropPartition o) {
            return List.of(new Stmt("ALTER TABLE " + tbl + " DROP PARTITION " + o.partitionName + (o.updateIndexes ? " UPDATE INDEXES" : "")));
        }
        if (op instanceof CreateSequence o) {
            List<String> parts = new ArrayList<>();
            if (o.startWith != null) parts.add("START WITH " + o.startWith);
            sequenceParts(parts, o.incrementBy, o.minValue, o.maxValue, o.cache);
            if (o.cycle) parts.add("CYCLE");
            if (o.order) parts.add("ORDER");
            return List.of(new Stmt("CREATE SEQUENCE " + s + "." + o.name + (parts.isEmpty() ? "" : " " + String.join(" ", parts))));
        }
        if (op instanceof AlterSequence o) {
            List<String> parts = new ArrayList<>();
            if (o.restartWith != null) parts.add("RESTART START WITH " + o.restartWith);
            sequenceParts(parts, o.incrementBy, o.minValue, o.maxValue, o.cache);
            if (o.cycle != null) parts.add(o.cycle ? "CYCLE" : "NOCYCLE");
            if (o.order != null) parts.add(o.order ? "ORDER" : "NOORDER");
            if (parts.isEmpty()) throw new IllegalArgumentException("Indica al menos un cambio para la secuencia");
            return List.of(new Stmt("ALTER SEQUENCE " + s + "." + o.name + " " + String.join(" ", parts)));
        }
        if (op instanceof CreateMvLog o) {
            List<String> withs = new ArrayList<>();
            if (o.withPrimaryKey) withs.add("PRIMARY KEY");
            if (o.withRowid) withs.add("ROWID");
            if (o.withSequence) withs.add("SEQUENCE");
            StringBuilder sql = new StringBuilder("CREATE MATERIALIZED VIEW LOG ON " + tbl + " WITH");
            if (!withs.isEmpty()) sql.append(" ").append(String.join(", ", withs));
            if (!o.columns.isEmpty()) sql.append(" (").append(String.join(", ", o.columns)).append(")");
            if (o.includingNewValues) sql.append(" INCLUDING NEW VALUES");
            return List.of(new Stmt(sql.toString()));
        }
        if (op instanceof DropMvLog) return List.of(new Stmt("DROP MATERIALIZED VIEW LOG ON " + tbl));
        if (op instanceof Revoke o) {
            return List.of(new Stmt("REVOKE " + String.join(", ", o.privileges) + " ON " + s + "." + o.object + " FROM " + o.grantee));
        }
        if (op instanceof Grant o) {
            String cols = o.columns.isEmpty() ? "" : " (" + String.join(", ", o.columns) + ")";
            String privs = o.privileges.stream().map(p -> p + cols).collect(Collectors.joining(", "));
            return List.of(new Stmt("GRANT " + privs + " ON " + s + "." + o.object + " TO " + o.grantee + (o.withGrantOption ? " WITH GRANT OPTION" : "")));
        }
        if (op instanceof CreateSynonym o) {
            String replace = o.replace ? "OR REPLACE " : "";
            if (s.equals("PUBLIC")) return List.of(new Stmt("CREATE " + replace + "PUBLIC SYNONYM " + o.synonym + " FOR " + o.targetSchema + "." + o.targetObject));
            return List.of(new Stmt("CREATE " + replace + "SYNONYM " + s + "." + o.synonym + " FOR " + o.targetSchema + "." + o.targetObject));
        }
        if (op instanceof DropSynonym o) {
            return List.of(new Stmt(s.equals("PUBLIC") ? "DROP PUBLIC SYNONYM " + o.synonym : "DROP SYNONYM " + s + "." + o.synonym));
        }
        if (op instanceof CreateUser o) {
            StringBuilder sql = new StringBuilder("CREATE USER " + s + " IDENTIFIED BY \"" + PASSWORD_PLACEHOLDER + "\"");
            if (o.defaultTablespace != null) sql.append(" DEFAULT TABLESPACE ").append(o.defaultTablespace);
            if (o.quota != null) sql.append(" QUOTA ").append(o.quota).append(" ON ").append(o.defaultTablespace != null ? o.defaultTablespace : "USERS");
            List<Stmt> st = new ArrayList<>(List.of(new Stmt(sql.toString(), false, PASSWORD_PLACEHOLDER)));
            if (!o.systemPrivileges.isEmpty()) st.add(new Stmt("GRANT " + String.join(", ", o.systemPrivileges) + " TO " + s));
            if (!o.roles.isEmpty()) st.add(new Stmt("GRANT " + String.join(", ", o.roles) + " TO " + s));
            return st;
        }
        if (op instanceof CreateOrReplaceCode o) {
            String src = o.source.strip();
            if (o.kind.equals("VIEW") || o.kind.equals("MATERIALIZED VIEW")) return List.of(new Stmt(src.replaceAll(";+$", "").stripTrailing()));
            if (!src.endsWith(";")) src += ";";
            return List.of(Stmt.plsql(src));
        }
        if (op instanceof DropCode o) return List.of(new Stmt("DROP " + o.kind + " " + s + "." + o.name));
        if (op instanceof CustomSql o) {
            List<Stmt> st = splitCustomSql(o.sql);
            if (st.isEmpty()) throw new IllegalArgumentException("El SQL personalizado está vacío");
            return st;
        }
        throw new IllegalArgumentException("Operación no soportada en Oracle: " + op.type);
    }

    private static void sequenceParts(List<String> parts, Long inc, Long min, Long max, Integer cache) {
        if (inc != null) parts.add("INCREMENT BY " + inc);
        if (min != null) parts.add("MINVALUE " + min);
        if (max != null) parts.add("MAXVALUE " + max);
        if (cache != null) parts.add(cache > 1 ? "CACHE " + cache : "NOCACHE");
    }

    private static String emptyNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    private static String truncate(String s) {
        return s.length() > 128 ? s.substring(0, 128) : s;
    }

    public static String script(List<Stmt> statements) {
        return statements.stream().map(Stmt::scriptText).collect(Collectors.joining("\n\n"));
    }

    static String statementsJson(List<Stmt> st) {
        return Json.write(st);
    }

    /** Lista de etiquetas para la interfaz. */
    public static List<Map<String, Object>> operationsMeta() {
        List<Map<String, Object>> out = new ArrayList<>();
        Ops.LABELS.forEach((k, v) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", k);
            m.put("label", v);
            m.put("destructive", Ops.DESTRUCTIVE.contains(k));
            out.add(m);
        });
        return out;
    }
}
