package dbamanager.ops;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import dbamanager.ops.V.Errs;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static dbamanager.ops.V.*;

/**
 * Operaciones que se pueden solicitar, independientes del motor. Cada motor las traduce a su SQL.
 * En JSON los campos van en snake_case y el tipo en "type".
 */
public final class Ops {
    private Ops() {}

    public static final List<String> OBJECT_PRIVILEGES = List.of("SELECT", "INSERT", "UPDATE", "DELETE", "EXECUTE",
            "REFERENCES", "ALTER", "INDEX", "READ", "ON COMMIT REFRESH", "QUERY REWRITE", "DEBUG", "FLASHBACK");
    public static final Set<String> COLUMN_PRIVILEGES = Set.of("INSERT", "UPDATE", "REFERENCES");
    public static final List<String> SYSTEM_PRIVILEGES = List.of("CREATE SESSION", "CREATE SYNONYM", "CREATE VIEW",
            "CREATE TABLE", "CREATE SEQUENCE", "CREATE PROCEDURE", "CREATE TRIGGER", "CREATE TYPE",
            "CREATE MATERIALIZED VIEW");
    public static final Set<String> CODE_KINDS = Set.of("PROCEDURE", "FUNCTION", "PACKAGE", "PACKAGE BODY", "TRIGGER",
            "VIEW", "MATERIALIZED VIEW", "TYPE");
    static final Set<String> COMPRESSIONS = Set.of("NONE", "BASIC", "ADVANCED");

    public static final Map<String, String> LABELS = new LinkedHashMap<>();
    static {
        LABELS.put("CREATE_TABLE", "Crear tabla");
        LABELS.put("ADD_COLUMN", "Agregar columna");
        LABELS.put("MODIFY_COLUMN", "Modificar columna");
        LABELS.put("DROP_COLUMN", "Eliminar columna");
        LABELS.put("RENAME", "Renombrar (tabla, columna, restricción, índice)");
        LABELS.put("SET_COMMENT", "Comentario de tabla/columna");
        LABELS.put("ALTER_TABLE_OPTIONS", "Opciones de tabla (logging, compresión, solo lectura)");
        LABELS.put("DROP_TABLE", "Eliminar tabla");
        LABELS.put("ADD_CONSTRAINT", "Agregar restricción (PK, única, foránea, check)");
        LABELS.put("DROP_CONSTRAINT", "Eliminar restricción");
        LABELS.put("CONSTRAINT_STATE", "Habilitar/deshabilitar restricción");
        LABELS.put("CREATE_INDEX", "Crear índice");
        LABELS.put("ALTER_INDEX", "Reconstruir índice / cambiar visibilidad");
        LABELS.put("DROP_INDEX", "Eliminar índice");
        LABELS.put("ADD_PARTITION", "Agregar partición");
        LABELS.put("DROP_PARTITION", "Eliminar partición");
        LABELS.put("CREATE_SEQUENCE", "Crear secuencia");
        LABELS.put("ALTER_SEQUENCE", "Modificar secuencia");
        LABELS.put("CREATE_MV_LOG", "Crear log de vista materializada");
        LABELS.put("DROP_MV_LOG", "Eliminar log de vista materializada");
        LABELS.put("GRANT", "Otorgar permisos");
        LABELS.put("REVOKE", "Revocar permisos");
        LABELS.put("CREATE_SYNONYM", "Crear sinónimo");
        LABELS.put("DROP_SYNONYM", "Eliminar sinónimo");
        LABELS.put("CREATE_USER", "Crear usuario");
        LABELS.put("CREATE_OR_REPLACE_CODE", "Crear/actualizar código (procedimiento, vista, trigger, vista materializada…)");
        LABELS.put("DROP_CODE", "Eliminar código/vista/secuencia/trigger");
        LABELS.put("CUSTOM_SQL", "SQL personalizado");
    }
    public static final Set<String> DESTRUCTIVE = Set.of("DROP_TABLE", "DROP_COLUMN", "DROP_SYNONYM", "DROP_CODE",
            "REVOKE", "CUSTOM_SQL", "MODIFY_COLUMN", "DROP_CONSTRAINT", "CONSTRAINT_STATE", "DROP_INDEX",
            "DROP_PARTITION", "DROP_MV_LOG", "RENAME", "ALTER_TABLE_OPTIONS");

    // ------------------------------------------------------------------ partes
    public static class Column {
        public String name;
        public String dataType;
        public Integer length;
        public boolean charSemantics;
        public Integer precision;
        public Integer scale;
        public boolean nullable = true;
        @JsonIgnore public String defaultValue;
        public boolean defaultOnNull;
        public String comment;
        public boolean identity;
        public String identityMode = "BY_DEFAULT_ON_NULL";
        public Long identityStart;
        public Long identityIncrement;
        public String virtualExpression;
        public boolean invisible;

        // "default" es palabra reservada en Java
        @com.fasterxml.jackson.annotation.JsonProperty("default")
        public String getDefault() { return defaultValue; }
        @com.fasterxml.jackson.annotation.JsonProperty("default")
        public void setDefault(String v) { defaultValue = v; }

        void validate(Errs e) {
            name = ident(e, "name", name);
            if (dataType != null && dataType.isBlank()) dataType = null;
            if (dataType != null) {
                dataType = dataType.strip().toUpperCase();
                if (!dataType.matches("^[A-Z][A-Z0-9_ ]{0,40}$")) e.add("data_type", "Tipo de dato inválido: " + dataType);
            }
            range(e, "length", length, 1, 32767);
            range(e, "precision", precision, -1, 126);
            range(e, "scale", scale, -84, 127);
            maxLen(e, "comment", comment, 4000);
            oneOf(e, "identity_mode", identityMode, Set.of("BY_DEFAULT_ON_NULL", "BY_DEFAULT", "ALWAYS"));
            defaultValue = safeExpr(e, "default", defaultValue, "El valor por defecto");
            virtualExpression = safeExpr(e, "virtual_expression", virtualExpression, "La expresión virtual");
            if (dataType == null && virtualExpression == null) e.add("data_type", name + ": indica el tipo de dato");
            if (virtualExpression != null && (identity || defaultValue != null))
                e.add("", name + ": una columna virtual no puede tener DEFAULT ni ser identidad");
            if (identity && defaultValue != null) e.add("", name + ": una columna identidad no lleva DEFAULT");
            if (defaultOnNull && defaultValue == null) e.add("", name + ": DEFAULT ON NULL requiere un valor por defecto");
        }
    }

    public static class ConstraintDef {
        public String kind;
        public String name;
        public List<String> columns = new ArrayList<>();
        public String refSchema;
        public String refTable;
        public List<String> refColumns = new ArrayList<>();
        public String onDelete = "NO_ACTION";
        public String condition;
        public boolean deferrable;
        public boolean initiallyDeferred;
        public boolean enabled = true;
        public boolean validated = true;
        public String indexTablespace;

        void validate(Errs e) {
            required(e, "kind", kind);
            oneOf(e, "kind", kind, Set.of("PRIMARY_KEY", "UNIQUE", "FOREIGN_KEY", "CHECK"));
            oneOf(e, "on_delete", onDelete, Set.of("NO_ACTION", "CASCADE", "SET_NULL"));
            name = optIdent(e, "name", name);
            refSchema = optIdent(e, "ref_schema", refSchema);
            refTable = optIdent(e, "ref_table", refTable);
            indexTablespace = optIdent(e, "index_tablespace", indexTablespace);
            columns = idents(e, "columns", columns);
            refColumns = idents(e, "ref_columns", refColumns);
            condition = safeExpr(e, "condition", condition, "La condición CHECK");
            if ("CHECK".equals(kind)) {
                if (condition == null) e.add("condition", "Una restricción CHECK requiere condición");
            } else if (columns.isEmpty()) {
                e.add("columns", "La restricción requiere columnas");
            }
            if ("FOREIGN_KEY".equals(kind)) {
                if (refTable == null) e.add("ref_table", "La llave foránea requiere la tabla referenciada");
                if (!refColumns.isEmpty() && refColumns.size() != columns.size())
                    e.add("ref_columns", "La llave foránea debe tener el mismo número de columnas que las referenciadas");
            }
            if (initiallyDeferred && !deferrable) e.add("initially_deferred", "INITIALLY DEFERRED requiere DEFERRABLE");
        }
    }

    public static class PartitionDef {
        public String name;
        public String values;
        public String tablespace;

        void validate(Errs e) {
            name = ident(e, "name", name);
            tablespace = optIdent(e, "tablespace", tablespace);
            values = safeExpr(e, "values", values, "Los valores de la partición", true);
        }
    }

    public static class Partitioning {
        public String method;
        public List<String> columns = new ArrayList<>();
        public String interval;
        public List<PartitionDef> partitions = new ArrayList<>();
        public Integer hashCount;

        void validate(Errs e) {
            required(e, "method", method);
            oneOf(e, "method", method, Set.of("RANGE", "LIST", "HASH"));
            if (columns == null || columns.isEmpty()) e.add("columns", "Debe tener al menos 1 elemento(s)");
            columns = idents(e, "columns", columns);
            interval = safeExpr(e, "interval", interval, "El intervalo");
            range(e, "hash_count", hashCount, 1, 1024);
            for (int i = 0; i < partitions.size(); i++) partitions.get(i).validate(nested(e, "partitions." + i));
            if (interval != null && (!"RANGE".equals(method) || columns.size() != 1))
                e.add("interval", "INTERVAL solo aplica a particionado RANGE de una columna");
            if ("RANGE".equals(method) || "LIST".equals(method)) {
                if (partitions.isEmpty()) e.add("partitions", "El particionado " + method + " requiere al menos una partición");
                if (partitions.stream().anyMatch(p -> p.values == null))
                    e.add("partitions", "Cada partición RANGE/LIST requiere sus valores");
            } else if (partitions.isEmpty() && hashCount == null) {
                e.add("hash_count", "El particionado HASH requiere número de particiones o sus nombres");
            }
        }
    }

    public static class IndexColumn {
        public String expression;
        public boolean descending;

        void validate(Errs e) {
            String v = expression == null ? "" : expression.strip();
            if (IDENT.matcher(v).matches()) expression = v.toUpperCase();
            else {
                String s = safeExpr(e, "expression", v, "La expresión del índice");
                expression = s == null ? "" : s;
                if (s == null) e.add("expression", "Campo obligatorio");
            }
        }

        @JsonIgnore
        public boolean isColumn() {
            return expression != null && IDENT.matcher(expression).matches();
        }
    }

    // ------------------------------------------------------------------ operaciones
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "type", visible = true)
    @JsonSubTypes({
            @JsonSubTypes.Type(value = CreateTable.class, name = "CREATE_TABLE"),
            @JsonSubTypes.Type(value = AddColumn.class, name = "ADD_COLUMN"),
            @JsonSubTypes.Type(value = ModifyColumn.class, name = "MODIFY_COLUMN"),
            @JsonSubTypes.Type(value = DropColumn.class, name = "DROP_COLUMN"),
            @JsonSubTypes.Type(value = DropTable.class, name = "DROP_TABLE"),
            @JsonSubTypes.Type(value = AlterTableOptions.class, name = "ALTER_TABLE_OPTIONS"),
            @JsonSubTypes.Type(value = Rename.class, name = "RENAME"),
            @JsonSubTypes.Type(value = SetComment.class, name = "SET_COMMENT"),
            @JsonSubTypes.Type(value = AddConstraint.class, name = "ADD_CONSTRAINT"),
            @JsonSubTypes.Type(value = DropConstraint.class, name = "DROP_CONSTRAINT"),
            @JsonSubTypes.Type(value = ConstraintState.class, name = "CONSTRAINT_STATE"),
            @JsonSubTypes.Type(value = CreateIndex.class, name = "CREATE_INDEX"),
            @JsonSubTypes.Type(value = DropIndex.class, name = "DROP_INDEX"),
            @JsonSubTypes.Type(value = AlterIndex.class, name = "ALTER_INDEX"),
            @JsonSubTypes.Type(value = AddPartition.class, name = "ADD_PARTITION"),
            @JsonSubTypes.Type(value = DropPartition.class, name = "DROP_PARTITION"),
            @JsonSubTypes.Type(value = CreateSequence.class, name = "CREATE_SEQUENCE"),
            @JsonSubTypes.Type(value = AlterSequence.class, name = "ALTER_SEQUENCE"),
            @JsonSubTypes.Type(value = CreateMvLog.class, name = "CREATE_MV_LOG"),
            @JsonSubTypes.Type(value = DropMvLog.class, name = "DROP_MV_LOG"),
            @JsonSubTypes.Type(value = Grant.class, name = "GRANT"),
            @JsonSubTypes.Type(value = Revoke.class, name = "REVOKE"),
            @JsonSubTypes.Type(value = CreateSynonym.class, name = "CREATE_SYNONYM"),
            @JsonSubTypes.Type(value = DropSynonym.class, name = "DROP_SYNONYM"),
            @JsonSubTypes.Type(value = CreateUser.class, name = "CREATE_USER"),
            @JsonSubTypes.Type(value = CreateOrReplaceCode.class, name = "CREATE_OR_REPLACE_CODE"),
            @JsonSubTypes.Type(value = DropCode.class, name = "DROP_CODE"),
            @JsonSubTypes.Type(value = CustomSql.class, name = "CUSTOM_SQL"),
    })
    public abstract static class Op {
        public String type;
        /** Esquema afectado (dueño del objeto, o PUBLIC en sinónimos públicos). */
        public String schema;

        abstract void check(Errs e);

        public final void validate(Errs e) {
            schema = ident(e, "schema", schema);
            check(e);
        }

        /** Nombre del objeto principal (para listados). */
        @JsonIgnore
        public String objectName() {
            return null;
        }

        /** Todos los esquemas que la operación modifica o referencia. */
        @JsonIgnore
        public Set<String> schemas() {
            return new TreeSet<>(Set.of(schema));
        }
    }

    public abstract static class TableOp extends Op {
        public String table;

        @Override
        void check(Errs e) {
            table = ident(e, "table", table);
            checkTable(e);
        }

        void checkTable(Errs e) {}

        @Override
        public String objectName() {
            return table;
        }
    }

    public static class CreateTable extends TableOp {
        public String comment;
        public List<Column> columns = new ArrayList<>();
        public List<String> primaryKey = new ArrayList<>();
        public String primaryKeyName;
        public String primaryKeyTablespace;
        public List<ConstraintDef> constraints = new ArrayList<>();
        public String tablespace;
        public String temporary;
        public String compression;
        public Boolean logging;
        public Partitioning partitioning;

        @Override
        void checkTable(Errs e) {
            maxLen(e, "comment", comment, 4000);
            if (columns == null || columns.isEmpty()) e.add("columns", "Debe tener al menos 1 elemento(s)");
            for (int i = 0; i < columns.size(); i++) columns.get(i).validate(nested(e, "columns." + i));
            tablespace = optIdent(e, "tablespace", tablespace);
            primaryKeyName = optIdent(e, "primary_key_name", primaryKeyName);
            primaryKeyTablespace = optIdent(e, "primary_key_tablespace", primaryKeyTablespace);
            primaryKey = idents(e, "primary_key", primaryKey);
            oneOf(e, "temporary", temporary, Set.of("DELETE_ROWS", "PRESERVE_ROWS"));
            oneOf(e, "compression", compression, COMPRESSIONS);
            for (int i = 0; i < constraints.size(); i++) constraints.get(i).validate(nested(e, "constraints." + i));
            if (partitioning != null) partitioning.validate(nested(e, "partitioning"));
            List<String> names = columns.stream().map(c -> c.name).toList();
            if (new HashSet<>(names).size() != names.size()) e.add("columns", "Columnas duplicadas");
            Set<String> missing = new TreeSet<>(primaryKey);
            for (ConstraintDef c : constraints) {
                if ("PRIMARY_KEY".equals(c.kind)) e.add("constraints", "La llave primaria se define con 'primary_key', no en 'constraints'");
                missing.addAll(c.columns);
            }
            if (partitioning != null) missing.addAll(partitioning.columns);
            missing.removeAll(names);
            if (!missing.isEmpty()) e.add("", "Columnas inexistentes en restricciones/particiones: " + String.join(", ", missing));
            if (temporary != null && (partitioning != null || tablespace != null || compression != null))
                e.add("temporary", "Una tabla temporal no admite particiones, tablespace ni compresión");
        }

        @Override
        public Set<String> schemas() {
            Set<String> out = super.schemas();
            for (ConstraintDef c : constraints) if (c.refSchema != null) out.add(c.refSchema);
            return out;
        }
    }

    public static class AddColumn extends TableOp {
        public Column column;

        @Override
        void checkTable(Errs e) {
            if (column == null) e.add("column", "Campo obligatorio");
            else column.validate(nested(e, "column"));
        }
    }

    public static class ModifyColumn extends TableOp {
        public Column column;
        /** Definición actual (del catálogo) para generar solo el cambio. */
        public Column previous;

        @Override
        void checkTable(Errs e) {
            if (column == null) e.add("column", "Campo obligatorio");
            else column.validate(nested(e, "column"));
            if (previous != null) previous.validate(nested(e, "previous"));
        }
    }

    public static class DropColumn extends TableOp {
        public String columnName;

        @Override
        void checkTable(Errs e) {
            columnName = ident(e, "column_name", columnName);
        }
    }

    public static class DropTable extends TableOp {
        public boolean cascadeConstraints;
        public boolean purge;
    }

    public static class AlterTableOptions extends TableOp {
        public Boolean logging;
        public String compression;
        public Boolean readOnly;

        @Override
        void checkTable(Errs e) {
            oneOf(e, "compression", compression, COMPRESSIONS);
            if (logging == null && compression == null && readOnly == null) e.add("", "Indica al menos una opción a cambiar");
        }
    }

    public static class Rename extends Op {
        public String target;
        public String table;
        public String oldName;
        public String newName;

        @Override
        void check(Errs e) {
            required(e, "target", target);
            oneOf(e, "target", target, Set.of("TABLE", "COLUMN", "CONSTRAINT", "INDEX"));
            oldName = ident(e, "old_name", oldName);
            newName = ident(e, "new_name", newName);
            table = optIdent(e, "table", table);
            if ("TABLE".equals(target)) table = oldName;
            if (("COLUMN".equals(target) || "CONSTRAINT".equals(target)) && table == null) e.add("table", "Indica la tabla");
            if (oldName != null && oldName.equals(newName)) e.add("new_name", "El nombre nuevo es igual al actual");
        }

        @Override
        public String objectName() {
            return table != null ? table : oldName;
        }
    }

    public static class SetComment extends TableOp {
        public String column;
        public String comment = "";

        @Override
        void checkTable(Errs e) {
            column = optIdent(e, "column", column);
            if (comment == null) comment = "";
            maxLen(e, "comment", comment, 4000);
        }
    }

    public static class AddConstraint extends TableOp {
        public ConstraintDef constraint;

        @Override
        void checkTable(Errs e) {
            if (constraint == null) e.add("constraint", "Campo obligatorio");
            else constraint.validate(nested(e, "constraint"));
        }

        @Override
        public Set<String> schemas() {
            Set<String> out = super.schemas();
            if (constraint != null && constraint.refSchema != null) out.add(constraint.refSchema);
            return out;
        }
    }

    public static class DropConstraint extends TableOp {
        public String constraintName;
        public boolean cascade;
        public String index;

        @Override
        void checkTable(Errs e) {
            constraintName = ident(e, "constraint_name", constraintName);
            oneOf(e, "index", index, Set.of("KEEP", "DROP"));
        }
    }

    public static class ConstraintState extends TableOp {
        public String constraintName;
        public String action;
        public boolean validateExisting = true;

        @Override
        void checkTable(Errs e) {
            constraintName = ident(e, "constraint_name", constraintName);
            required(e, "action", action);
            oneOf(e, "action", action, Set.of("ENABLE", "DISABLE"));
        }
    }

    public static class CreateIndex extends TableOp {
        public String indexName;
        public String indexType = "NORMAL";
        public List<IndexColumn> columns = new ArrayList<>();
        public boolean reverse;
        public String compression;
        public Integer prefixLength;
        public String tablespace;
        public boolean local;
        public boolean invisible;
        public boolean online;
        public String indextype;
        public String parameters;

        @Override
        void checkTable(Errs e) {
            indexName = ident(e, "index_name", indexName);
            oneOf(e, "index_type", indexType, Set.of("NORMAL", "UNIQUE", "BITMAP", "DOMAIN"));
            if (columns == null || columns.isEmpty()) e.add("columns", "Debe tener al menos 1 elemento(s)");
            for (int i = 0; i < columns.size(); i++) columns.get(i).validate(nested(e, "columns." + i));
            oneOf(e, "compression", compression, Set.of("PREFIX", "ADVANCED_LOW", "ADVANCED_HIGH"));
            range(e, "prefix_length", prefixLength, 1, 32);
            tablespace = optIdent(e, "tablespace", tablespace);
            indextype = indextype == null || indextype.isBlank() ? null : qualified(e, "indextype", indextype);
            maxLen(e, "parameters", parameters, 1000);
            if ("DOMAIN".equals(indexType)) {
                if (indextype == null) e.add("indextype", "Un índice de dominio requiere INDEXTYPE (p. ej. CTXSYS.CONTEXT)");
                if (columns.size() != 1 || !columns.get(0).isColumn() || columns.get(0).descending)
                    e.add("columns", "Un índice de dominio va sobre una sola columna, sin DESC");
                if (reverse || compression != null || local || online)
                    e.add("", "Un índice de dominio no admite REVERSE, compresión, LOCAL ni ONLINE");
            } else if (indextype != null || parameters != null) {
                e.add("indextype", "INDEXTYPE/PARAMETERS solo aplican a índices de dominio");
            }
            if ("BITMAP".equals(indexType) && reverse) e.add("reverse", "Un índice BITMAP no puede ser REVERSE");
            if (reverse && columns.stream().anyMatch(c -> !c.isColumn() || c.descending))
                e.add("reverse", "REVERSE no admite expresiones ni columnas DESC");
            int maxPrefix = columns.size() - ("UNIQUE".equals(indexType) ? 1 : 0);
            if ("PREFIX".equals(compression) && prefixLength != null && prefixLength > maxPrefix)
                e.add("prefix_length", "La longitud de prefijo de compresión no puede ser mayor a " + maxPrefix);
            if ("PREFIX".equals(compression) && "UNIQUE".equals(indexType) && columns.size() < 2)
                e.add("compression", "Un índice único de una sola columna no se puede comprimir por prefijo");
            if (compression != null && !"PREFIX".equals(compression) && "BITMAP".equals(indexType))
                e.add("compression", "La compresión avanzada no aplica a índices BITMAP");
        }

        @Override
        public String objectName() {
            return indexName;
        }
    }

    public static class DropIndex extends Op {
        public String indexName;
        public String table;
        public boolean online;

        @Override
        void check(Errs e) {
            indexName = ident(e, "index_name", indexName);
            table = optIdent(e, "table", table);
        }

        @Override
        public String objectName() {
            return indexName;
        }
    }

    public static class AlterIndex extends Op {
        public String indexName;
        public String table;
        public String action;
        public boolean online;
        public String tablespace;

        @Override
        void check(Errs e) {
            indexName = ident(e, "index_name", indexName);
            table = optIdent(e, "table", table);
            tablespace = optIdent(e, "tablespace", tablespace);
            required(e, "action", action);
            oneOf(e, "action", action, Set.of("REBUILD", "VISIBLE", "INVISIBLE"));
        }

        @Override
        public String objectName() {
            return indexName;
        }
    }

    public static class AddPartition extends TableOp {
        public String method;
        public PartitionDef partition;

        @Override
        void checkTable(Errs e) {
            required(e, "method", method);
            oneOf(e, "method", method, Set.of("RANGE", "LIST", "HASH"));
            if (partition == null) {
                e.add("partition", "Campo obligatorio");
                return;
            }
            partition.validate(nested(e, "partition"));
            if (!"HASH".equals(method) && partition.values == null) e.add("partition.values", "La partición requiere sus valores");
        }
    }

    public static class DropPartition extends TableOp {
        public String partitionName;
        public boolean updateIndexes = true;

        @Override
        void checkTable(Errs e) {
            partitionName = ident(e, "partition_name", partitionName);
        }
    }

    public static class CreateSequence extends Op {
        public String name;
        public Long startWith;
        public Long incrementBy;
        public Long minValue;
        public Long maxValue;
        public Integer cache;
        public boolean cycle;
        public boolean order;

        @Override
        void check(Errs e) {
            name = ident(e, "name", name);
            if (cache != null && cache < 0) e.add("cache", "Debe ser mayor o igual a 0");
        }

        @Override
        public String objectName() {
            return name;
        }
    }

    public static class AlterSequence extends Op {
        public String name;
        public Long restartWith;
        public Long incrementBy;
        public Long minValue;
        public Long maxValue;
        public Integer cache;
        public Boolean cycle;
        public Boolean order;

        @Override
        void check(Errs e) {
            name = ident(e, "name", name);
            if (cache != null && cache < 0) e.add("cache", "Debe ser mayor o igual a 0");
        }

        @Override
        public String objectName() {
            return name;
        }
    }

    public static class CreateMvLog extends TableOp {
        public boolean withPrimaryKey = true;
        public boolean withRowid;
        public boolean withSequence;
        public List<String> columns = new ArrayList<>();
        public boolean includingNewValues;

        @Override
        void checkTable(Errs e) {
            columns = idents(e, "columns", columns);
            if (!(withPrimaryKey || withRowid || withSequence || !columns.isEmpty()))
                e.add("", "Indica PRIMARY KEY, ROWID, SEQUENCE o columnas");
        }
    }

    public static class DropMvLog extends TableOp {}

    public static class Grant extends Op {
        public String object;
        public String grantee;
        public List<String> privileges = new ArrayList<>();
        public List<String> columns = new ArrayList<>();
        public boolean withGrantOption;

        @Override
        void check(Errs e) {
            object = ident(e, "object", object);
            grantee = ident(e, "grantee", grantee);
            columns = idents(e, "columns", columns);
            if (privileges == null || privileges.isEmpty()) {
                e.add("privileges", "Debe tener al menos 1 elemento(s)");
                privileges = new ArrayList<>();
            }
            Set<String> norm = new TreeSet<>();
            for (String p : privileges) norm.add(p == null ? "" : p.strip().toUpperCase().replaceAll("\\s+", " "));
            privileges = new ArrayList<>(norm);
            List<String> bad = privileges.stream().filter(p -> !OBJECT_PRIVILEGES.contains(p)).toList();
            if (!bad.isEmpty()) e.add("privileges", "Privilegios no permitidos: " + String.join(", ", bad));
            if (!columns.isEmpty() && !COLUMN_PRIVILEGES.containsAll(privileges))
                e.add("columns", "Por columna solo se otorgan INSERT, UPDATE o REFERENCES");
        }

        @Override
        public String objectName() {
            return object;
        }
    }

    public static class Revoke extends Grant {
        @Override
        void check(Errs e) {
            super.check(e);
            if (!columns.isEmpty()) e.add("columns", "Oracle revoca a nivel tabla; quita las columnas");
        }
    }

    public static class CreateSynonym extends Op {
        public String synonym;
        public String targetSchema;
        public String targetObject;
        public boolean replace = true;

        @Override
        void check(Errs e) {
            synonym = ident(e, "synonym", synonym);
            targetSchema = ident(e, "target_schema", targetSchema);
            targetObject = ident(e, "target_object", targetObject);
        }

        @Override
        public String objectName() {
            return synonym;
        }

        @Override
        public Set<String> schemas() {
            Set<String> out = super.schemas();
            if (targetSchema != null) out.add(targetSchema);
            return out;
        }
    }

    public static class DropSynonym extends Op {
        public String synonym;

        @Override
        void check(Errs e) {
            synonym = ident(e, "synonym", synonym);
        }

        @Override
        public String objectName() {
            return synonym;
        }
    }

    public static class CreateUser extends Op {
        public String kind = "READER";
        public String defaultTablespace;
        public String quota;
        public List<String> systemPrivileges = new ArrayList<>(List.of("CREATE SESSION"));
        public List<String> roles = new ArrayList<>();

        @Override
        void check(Errs e) {
            oneOf(e, "kind", kind, Set.of("READER", "OWNER"));
            defaultTablespace = optIdent(e, "default_tablespace", defaultTablespace);
            roles = idents(e, "roles", roles);
            if (quota != null && quota.isBlank()) quota = null;
            if (quota != null && !quota.matches("^(UNLIMITED|\\d{1,6}[KMG])$")) e.add("quota", "Formato inválido");
            List<String> sp = new ArrayList<>();
            for (String p : systemPrivileges == null ? List.<String>of() : systemPrivileges) sp.add(p.strip().toUpperCase());
            systemPrivileges = sp;
            List<String> bad = sp.stream().filter(p -> !SYSTEM_PRIVILEGES.contains(p)).toList();
            if (!bad.isEmpty()) e.add("system_privileges", "Privilegios de sistema no permitidos: " + String.join(", ", bad));
        }

        @Override
        public String objectName() {
            return schema;
        }
    }

    private static final Pattern CODE_HEADER = Pattern.compile(
            "^CREATE\\s+(?<replace>OR\\s+REPLACE\\s+)?(?:(?:EDITIONABLE|NONEDITIONABLE|FORCE|NO\\s+FORCE)\\s+)*"
                    + "(?<kind>PACKAGE\\s+BODY|PACKAGE|PROCEDURE|FUNCTION|TRIGGER|MATERIALIZED\\s+VIEW|VIEW|TYPE)\\s+"
                    + "(?:\"?(?<owner>[A-Za-z][\\w$#]*)\"?\\s*\\.\\s*)?(?<q>\"?)(?<name>[A-Za-z][\\w$#]*)\"?",
            Pattern.CASE_INSENSITIVE);

    public static class CreateOrReplaceCode extends Op {
        public String kind;
        public String name;
        public String source;

        @Override
        void check(Errs e) {
            name = ident(e, "name", name);
            required(e, "kind", kind);
            oneOf(e, "kind", kind, CODE_KINDS);
            if (source == null || source.length() < 10) {
                e.add("source", "Debe tener al menos 10 caracteres");
                return;
            }
            if (source.length() > 200_000) {
                e.add("source", "Debe tener máximo 200000 caracteres");
                return;
            }
            String src = source.strip().replaceAll("/+$", "").strip();
            Matcher m = CODE_HEADER.matcher(src);
            if (!m.find()) {
                e.add("source", "El código debe iniciar con CREATE [OR REPLACE] <TIPO> <ESQUEMA>.<NOMBRE>");
                return;
            }
            String k = m.group("kind").toUpperCase().replaceAll("\\s+", " ");
            String owner = m.group("owner") == null ? "" : m.group("owner").toUpperCase();
            String n = m.group("name").toUpperCase();
            if (!k.equals(kind)) e.add("source", "El código declara " + k + " pero la operación es " + kind);
            if (k.equals("MATERIALIZED VIEW") && m.group("replace") != null)
                e.add("source", "Oracle no admite CREATE OR REPLACE MATERIALIZED VIEW; usa CREATE MATERIALIZED VIEW");
            if (!owner.isEmpty() && !owner.equals(schema))
                e.add("source", "El código crea en el esquema " + owner + " pero la operación es para " + schema);
            if (!n.equals(name)) e.add("source", "El código crea " + n + " pero la operación es para " + name);
            if (owner.isEmpty()) {
                // Sin esquema explícito se crearía en la cuenta ejecutora: se califica siempre.
                int idx = m.start("q");
                src = src.substring(0, idx) + schema + "." + src.substring(idx);
            }
            source = src;
        }

        @Override
        public String objectName() {
            return name;
        }
    }

    public static class DropCode extends Op {
        public String kind;
        public String name;

        @Override
        void check(Errs e) {
            name = ident(e, "name", name);
            required(e, "kind", kind);
            Set<String> kinds = new LinkedHashSet<>(CODE_KINDS);
            kinds.add("SEQUENCE");
            oneOf(e, "kind", kind, kinds);
        }

        @Override
        public String objectName() {
            return name;
        }
    }

    public static class CustomSql extends Op {
        public String description;
        public String sql;

        @Override
        void check(Errs e) {
            if (description == null || description.length() < 5) e.add("description", "Debe tener al menos 5 caracteres");
            maxLen(e, "description", description, 1000);
            if (sql == null || sql.length() < 5) e.add("sql", "Debe tener al menos 5 caracteres");
            maxLen(e, "sql", sql, 200_000);
        }
    }
}
