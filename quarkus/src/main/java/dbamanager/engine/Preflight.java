package dbamanager.engine;

import dbamanager.ops.Ops.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Validación previa de operaciones contra el diccionario de Oracle (no ejecuta nada). Simula el
 * efecto de las operaciones anteriores de la misma solicitud (objetos, columnas, restricciones e
 * índices creados o eliminados) para que, por ejemplo, una tabla nueva y su índice se validen juntos.
 */
public class Preflight {
    /** q(sql, nombre1, valor1, nombre2, valor2...) -> filas con llaves en minúsculas. */
    public interface Query {
        List<Map<String, Object>> q(String sql, Object... binds);
    }

    private static final Map<String, String> CONS_KIND = Map.of("PRIMARY_KEY", "P", "UNIQUE", "U", "FOREIGN_KEY", "R", "CHECK", "C");
    private static final Set<String> WRITES = Set.of("ADD_COLUMN", "MODIFY_COLUMN", "DROP_COLUMN", "DROP_PARTITION",
            "ADD_CONSTRAINT", "CREATE_MV_LOG");

    private final Query db;
    private final List<Finding> findings = new ArrayList<>();
    private int seq;
    private final Set<String> users = new HashSet<>();
    /** (owner.name) -> tipo; null = eliminado. */
    private final Map<String, String> objects = new HashMap<>();
    private final Map<String, Map<String, Boolean>> columns = new HashMap<>();
    private final Map<String, String> constraints = new HashMap<>();
    private final Map<String, Boolean> pkTables = new HashMap<>();
    private final Map<String, Boolean> readOnly = new HashMap<>();
    private final Map<String, Integer> futureObjs = new HashMap<>();
    private final Map<String, Integer> futureUsers = new HashMap<>();
    private final Map<String, Integer> futureCols = new HashMap<>();

    public Preflight(Query db) {
        this.db = db;
    }

    private static String k(String... parts) {
        return String.join("\u0001", parts);
    }

    private void add(String level, String msg) {
        findings.add(new Finding(level, msg, seq));
    }

    private boolean exists(String sql, Object... binds) {
        return !db.q(sql, binds).isEmpty();
    }

    // ---------------------------------------------------------------- consultas con simulación
    boolean userExists(String u) {
        return u.equals("PUBLIC") || users.contains(u) || exists(
                "SELECT 1 FROM dba_users WHERE username = :u UNION ALL SELECT 1 FROM dba_roles WHERE role = :u", "u", u);
    }

    String objType(String owner, String name) {
        if (objects.containsKey(k(owner, name))) return objects.get(k(owner, name));
        List<Map<String, Object>> rows = db.q("""
                SELECT object_type FROM dba_objects WHERE owner = :o AND object_name = :n
                   AND object_type NOT IN ('PACKAGE BODY','TYPE BODY','TABLE PARTITION','INDEX PARTITION',
                                           'TABLE SUBPARTITION','INDEX SUBPARTITION','LOB','LOB PARTITION')
                 ORDER BY DECODE(object_type,'MATERIALIZED VIEW',0,'TABLE',1,'INDEX',2,3)""", "o", owner, "n", name);
        return rows.isEmpty() ? null : (String) rows.get(0).get("object_type");
    }

    boolean isTable(String owner, String name) {
        return "TABLE".equals(objType(owner, name));
    }

    boolean requireTable(String owner, String table) {
        if (!isTable(owner, table)) {
            add("error", "No existe la tabla " + owner + "." + table + later(futureObjs, k(owner, table)));
            return false;
        }
        return true;
    }

    boolean isReadOnly(String owner, String table) {
        if (readOnly.containsKey(k(owner, table))) return readOnly.get(k(owner, table));
        return exists("SELECT 1 FROM dba_tables WHERE owner = :o AND table_name = :t AND read_only = 'YES'", "o", owner, "t", table);
    }

    boolean colExists(String owner, String table, String col) {
        Map<String, Boolean> cols = columns.getOrDefault(k(owner, table), Map.of());
        if (cols.containsKey(col)) return cols.get(col);
        return exists("SELECT 1 FROM dba_tab_cols WHERE owner = :o AND table_name = :t AND column_name = :c",
                "o", owner, "t", table, "c", col);
    }

    void setCol(String owner, String table, String col, boolean exists) {
        columns.computeIfAbsent(k(owner, table), x -> new HashMap<>()).put(col, exists);
    }

    void requireCols(String owner, String table, List<String> cols, String what) {
        List<String> missing = cols.stream().filter(c -> !colExists(owner, table, c)).toList();
        if (!missing.isEmpty()) {
            String hint = missing.stream().map(c -> later(futureCols, k(owner, table, c))).filter(h -> !h.isEmpty()).findFirst().orElse("");
            add("error", what + "columna(s) inexistente(s) en " + owner + "." + table + ": " + String.join(", ", missing) + hint);
        }
    }

    /** Tipo de restricción (P/U/R/C) o null si no existe. */
    String constraint(String owner, String name) {
        if (constraints.containsKey(k(owner, name))) return constraints.get(k(owner, name));
        List<Map<String, Object>> rows = db.q("SELECT constraint_type FROM dba_constraints WHERE owner = :o AND constraint_name = :n",
                "o", owner, "n", name);
        return rows.isEmpty() ? null : (String) rows.get(0).get("constraint_type");
    }

    boolean hasPk(String owner, String table) {
        if (pkTables.containsKey(k(owner, table))) return pkTables.get(k(owner, table));
        return exists("SELECT 1 FROM dba_constraints WHERE owner = :o AND table_name = :t AND constraint_type = 'P'", "o", owner, "t", table);
    }

    List<Set<String>> keyColumns(String owner, String table) {
        Map<String, Set<String>> keys = new HashMap<>();
        for (Map<String, Object> r : db.q("""
                SELECT c.constraint_name, cc.column_name FROM dba_constraints c
                  JOIN dba_cons_columns cc ON cc.owner = c.owner AND cc.constraint_name = c.constraint_name
                 WHERE c.owner = :o AND c.table_name = :t AND c.constraint_type IN ('P','U')""", "o", owner, "t", table)) {
            keys.computeIfAbsent((String) r.get("constraint_name"), x -> new HashSet<>()).add((String) r.get("column_name"));
        }
        return new ArrayList<>(keys.values());
    }

    List<String> dependents(String owner, String name) {
        return db.q("""
                SELECT owner || '.' || name || ' (' || LOWER(type) || ')' AS d FROM dba_dependencies
                 WHERE referenced_owner = :o AND referenced_name = :n
                   AND NOT (owner = :o AND name = :n) FETCH FIRST 20 ROWS ONLY""", "o", owner, "n", name)
                .stream().map(r -> (String) r.get("d")).toList();
    }

    Map<String, Object> partitioning(String owner, String table) {
        List<Map<String, Object>> rows = db.q("""
                SELECT partitioning_type, interval, partition_count FROM dba_part_tables
                 WHERE owner = :o AND table_name = :t""", "o", owner, "t", table);
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ---------------------------------------------------------------- reglas comunes
    void checkFk(String owner, ConstraintDef c) {
        String refOwner = c.refSchema != null ? c.refSchema : owner;
        if (!isTable(refOwner, c.refTable)) {
            add("error", "La tabla referenciada " + refOwner + "." + c.refTable + " no existe" + later(futureObjs, k(refOwner, c.refTable)));
            return;
        }
        if (!c.refColumns.isEmpty()) {
            requireCols(refOwner, c.refTable, c.refColumns, "FK: ");
            if (!pkTables.containsKey(k(refOwner, c.refTable)) && !keyColumns(refOwner, c.refTable).contains(new HashSet<>(c.refColumns)))
                add("warning", "Las columnas referenciadas de " + refOwner + "." + c.refTable + " deben ser su PK o una llave única");
        } else if (!hasPk(refOwner, c.refTable)) {
            add("error", refOwner + "." + c.refTable + " no tiene llave primaria; indica las columnas referenciadas");
        }
        if (!refOwner.equals(owner))
            add("info", "El dueño " + owner + " necesita REFERENCES sobre " + refOwner + "." + c.refTable
                    + " (si no lo tiene, agrega un GRANT REFERENCES antes)");
    }

    void checkConstraint(String owner, String table, ConstraintDef c, boolean newTable) {
        if (c.name != null && constraint(owner, c.name) != null) add("error", "Ya existe una restricción llamada " + owner + "." + c.name);
        if (!c.columns.isEmpty()) requireCols(owner, table, c.columns, "");
        if (c.kind.equals("FOREIGN_KEY")) checkFk(owner, c);
        if (c.kind.equals("PRIMARY_KEY") && hasPk(owner, table)) add("error", owner + "." + table + " ya tiene llave primaria");
        if (!newTable && c.enabled && c.validated)
            add("info", "Si hay renglones que no cumplan la restricción, fallará (usa NOVALIDATE para no revisar los existentes)");
        if (c.name != null) constraints.put(k(owner, c.name), CONS_KIND.get(c.kind));
        if (c.kind.equals("PRIMARY_KEY")) pkTables.put(k(owner, table), true);
    }

    // ---------------------------------------------------------------- dependencias entre operaciones
    private void scanFuture(List<Op> ops) {
        for (int i = 0; i < ops.size(); i++) {
            int n = i + 1;
            Op op = ops.get(i);
            String s = op.schema;
            String obj = switch (op) {
                case CreateTable o -> o.table;
                case CreateIndex o -> o.indexName;
                case CreateSequence o -> o.name;
                case CreateOrReplaceCode o -> o.name;
                case CreateSynonym o -> o.synonym;
                default -> null;
            };
            if (obj != null) futureObjs.putIfAbsent(k(s, obj), n);
            if (op instanceof Rename r && (r.target.equals("TABLE") || r.target.equals("INDEX"))) futureObjs.putIfAbsent(k(s, r.newName), n);
            if (op instanceof CreateUser) futureUsers.putIfAbsent(s, n);
            if (op instanceof CreateTable ct) for (Column c : ct.columns) futureCols.putIfAbsent(k(s, ct.table, c.name), n);
            if (op instanceof AddColumn ac) futureCols.putIfAbsent(k(s, ac.table, ac.column.name), n);
            if (op instanceof Rename r && r.target.equals("COLUMN")) futureCols.putIfAbsent(k(s, r.table, r.newName), n);
        }
    }

    /** Texto adicional si lo que falta se crea en una operación posterior. */
    private String later(Map<String, Integer> future, String key) {
        Integer s = future.get(key);
        if (s != null && s > seq) {
            return " — se crea más adelante, en la operación #" + s + ". Las operaciones se ejecutan en el orden mostrado: mueve la #"
                    + s + " antes de la #" + seq;
        }
        return "";
    }

    public List<Finding> run(List<Op> ops) {
        scanFuture(ops);
        for (int i = 0; i < ops.size(); i++) {
            seq = i + 1;
            Op op = ops.get(i);
            List<String> internal = new ArrayList<>();
            for (String x : new TreeSet<>(op.schemas())) {
                if (!x.equals("PUBLIC") && exists("SELECT 1 FROM dba_users WHERE username = :u AND oracle_maintained = 'Y'", "u", x)) internal.add(x);
            }
            if (!internal.isEmpty()) add("error", "Esquema interno de Oracle: " + String.join(", ", internal) + "; no se permiten cambios ahí");
            String s = op.schema;
            if (!(op instanceof CreateUser) && !userExists(s))
                add("error", "El esquema/usuario " + s + " no existe en esta base" + later(futureUsers, s));
            if (WRITES.contains(op.type) && op instanceof TableOp t && isTable(s, t.table) && isReadOnly(s, t.table))
                add("error", s + "." + t.table + " está en solo lectura (READ ONLY); ponla en READ WRITE antes");
            dispatch(op, s);
        }
        return findings;
    }

    private void dispatch(Op op, String s) {
        switch (op) {
            case CreateTable o -> createTable(o, s);
            case AddColumn o -> addColumn(o, s);
            case ModifyColumn o -> modifyColumn(o, s);
            case DropColumn o -> dropColumn(o, s);
            case DropTable o -> dropTable(o, s);
            case AlterTableOptions o -> alterTableOptions(o, s);
            case Rename o -> rename(o, s);
            case SetComment o -> setComment(o, s);
            case AddConstraint o -> {
                if (requireTable(s, o.table)) checkConstraint(s, o.table, o.constraint, false);
            }
            case DropConstraint o -> dropConstraint(o, s);
            case ConstraintState o -> {
                if (requireTable(s, o.table) && constraint(s, o.constraintName) == null)
                    add("error", "No existe la restricción " + s + "." + o.constraintName);
                if (o.action.equals("DISABLE")) add("warning", "Mientras esté deshabilitada, la base no validará la regla");
            }
            case CreateIndex o -> createIndex(o, s);
            case DropIndex o -> dropIndex(o, s);
            case AlterIndex o -> alterIndex(o, s);
            case AddPartition o -> addPartition(o, s);
            case DropPartition o -> dropPartition(o, s);
            case CreateSequence o -> {
                if (objType(s, o.name) != null) add("error", "Ya existe un objeto llamado " + s + "." + o.name);
                objects.put(k(s, o.name), "SEQUENCE");
            }
            case AlterSequence o -> {
                if (!"SEQUENCE".equals(objType(s, o.name)))
                    add("error", "No existe la secuencia " + s + "." + o.name + later(futureObjs, k(s, o.name)));
                if (o.restartWith != null) add("warning", "Reiniciar la secuencia puede generar valores duplicados si ya se usaron");
            }
            case CreateMvLog o -> createMvLog(o, s);
            case DropMvLog o -> {
                if (requireTable(s, o.table) && !exists("SELECT 1 FROM dba_mview_logs WHERE log_owner = :o AND master = :t", "o", s, "t", o.table))
                    add("error", s + "." + o.table + " no tiene log de vista materializada");
                add("warning", "Las vistas materializadas con refresco FAST sobre esta tabla dejarán de poder refrescarse rápido");
            }
            case Grant o -> grant(o, s);
            case CreateSynonym o -> createSynonym(o, s);
            case DropSynonym o -> {
                if (!"SYNONYM".equals(objType(s, o.synonym))) add("error", "No existe el sinónimo " + s + "." + o.synonym);
                objects.put(k(s, o.synonym), null);
            }
            case CreateUser o -> {
                if (userExists(s)) add("error", "El usuario " + s + " ya existe");
                users.add(s);
                add("info", "La contraseña se genera al ejecutar y se muestra una sola vez al DBA ejecutor");
            }
            case CreateOrReplaceCode o -> createCode(o, s);
            case DropCode o -> dropCode(o, s);
            case CustomSql o -> add("warning", "SQL personalizado: revisar manualmente cada sentencia antes de aprobar");
            default -> { }
        }
    }

    private void createTable(CreateTable op, String s) {
        if (objType(s, op.table) != null) add("error", "Ya existe un objeto llamado " + s + "." + op.table);
        objects.put(k(s, op.table), "TABLE");
        for (Column c : op.columns) setCol(s, op.table, c.name, true);
        if (!op.primaryKey.isEmpty()) {
            if (op.primaryKeyName != null && constraint(s, op.primaryKeyName) != null)
                add("error", "Ya existe una restricción llamada " + s + "." + op.primaryKeyName);
            pkTables.put(k(s, op.table), true);
            if (op.primaryKeyName != null) constraints.put(k(s, op.primaryKeyName), "P");
        }
        for (ConstraintDef c : op.constraints) checkConstraint(s, op.table, c, true);
        if ("ADVANCED".equals(op.compression))
            add("info", "ROW STORE COMPRESS ADVANCED requiere licencia de Advanced Compression en Enterprise Edition");
        if (op.primaryKey.isEmpty() && op.temporary == null) add("warning", "La tabla no tiene llave primaria");
    }

    private void addColumn(AddColumn op, String s) {
        if (requireTable(s, op.table)) {
            Column c = op.column;
            if (colExists(s, op.table, c.name)) add("error", "La columna " + c.name + " ya existe en " + s + "." + op.table);
            if (!c.nullable && c.defaultValue == null && !c.identity && c.virtualExpression == null)
                add("warning", "Agregar " + c.name + " NOT NULL sin DEFAULT falla si la tabla tiene renglones");
            if (c.identity && exists("SELECT 1 FROM dba_tab_identity_cols WHERE owner = :o AND table_name = :t", "o", s, "t", op.table))
                add("error", s + "." + op.table + " ya tiene una columna identidad (solo se permite una)");
        }
        setCol(s, op.table, op.column.name, true);
    }

    private void modifyColumn(ModifyColumn op, String s) {
        if (!requireTable(s, op.table)) return;
        Column c = op.column, prev = op.previous;
        if (!colExists(s, op.table, c.name)) add("error", "La columna " + c.name + " no existe en " + s + "." + op.table);
        if (prev != null && prev.length != null && c.length != null && c.length < prev.length)
            add("warning", "Reducir " + c.name + " de " + prev.length + " a " + c.length + " falla si hay valores más largos");
        if (prev != null && prev.nullable && !c.nullable) add("info", "Poner " + c.name + " NOT NULL falla si existen valores nulos");
        if (prev != null && !Objects.equals(prev.dataType, c.dataType))
            add("warning", "Cambiar el tipo de " + c.name + " (" + prev.dataType + " → " + c.dataType + ") suele requerir la columna vacía");
    }

    private void dropColumn(DropColumn op, String s) {
        if (requireTable(s, op.table)) {
            if (!colExists(s, op.table, op.columnName)) add("error", "La columna " + op.columnName + " no existe en " + s + "." + op.table);
            List<Map<String, Object>> used = db.q("""
                    SELECT 'restricción ' || constraint_name AS u FROM dba_cons_columns
                     WHERE owner = :o AND table_name = :t AND column_name = :c
                    UNION ALL
                    SELECT 'índice ' || index_name FROM dba_ind_columns
                     WHERE table_owner = :o AND table_name = :t AND column_name = :c""", "o", s, "t", op.table, "c", op.columnName);
            if (!used.isEmpty()) add("warning", "La columna se usa en: " + used.stream().map(u -> (String) u.get("u")).collect(Collectors.joining(", ")));
        }
        setCol(s, op.table, op.columnName, false);
    }

    private void dropTable(DropTable op, String s) {
        if (!requireTable(s, op.table)) return;
        List<String> refs = db.q("""
                SELECT c.owner || '.' || c.table_name AS t FROM dba_constraints c
                  JOIN dba_constraints p ON p.owner = c.r_owner AND p.constraint_name = c.r_constraint_name
                 WHERE c.constraint_type = 'R' AND p.owner = :o AND p.table_name = :n
                   AND NOT (c.owner = :o AND c.table_name = :n)""", "o", s, "n", op.table).stream().map(r -> (String) r.get("t")).toList();
        if (!refs.isEmpty() && !op.cascadeConstraints)
            add("error", "Otras tablas la referencian (" + String.join(", ", refs) + "); se requiere CASCADE CONSTRAINTS");
        else if (!refs.isEmpty()) add("warning", "Se eliminarán llaves foráneas de: " + String.join(", ", refs));
        List<String> deps = dependents(s, op.table);
        if (!deps.isEmpty()) add("warning", "Quedarán inválidos: " + String.join(", ", deps));
        if (!op.purge) add("info", "Sin PURGE la tabla queda en la papelera (recuperable con FLASHBACK)");
        objects.put(k(s, op.table), null);
    }

    private void alterTableOptions(AlterTableOptions op, String s) {
        requireTable(s, op.table);
        if (op.readOnly != null) readOnly.put(k(s, op.table), op.readOnly);
        if ("ADVANCED".equals(op.compression))
            add("info", "ROW STORE COMPRESS ADVANCED requiere licencia de Advanced Compression en Enterprise Edition");
        if (op.compression != null) add("info", "La compresión aplica a los datos que se carguen después; los existentes no se recomprimen");
    }

    private void rename(Rename op, String s) {
        switch (op.target) {
            case "TABLE" -> {
                if (requireTable(s, op.oldName)) {
                    if (objType(s, op.newName) != null) add("error", "Ya existe un objeto llamado " + s + "." + op.newName);
                    List<String> deps = dependents(s, op.oldName);
                    if (!deps.isEmpty()) add("warning", "Quedarán inválidos (usan el nombre anterior): " + String.join(", ", deps));
                    objects.put(k(s, op.oldName), null);
                    objects.put(k(s, op.newName), "TABLE");
                }
            }
            case "COLUMN" -> {
                if (requireTable(s, op.table)) {
                    if (!colExists(s, op.table, op.oldName)) add("error", "La columna " + op.oldName + " no existe en " + s + "." + op.table);
                    if (colExists(s, op.table, op.newName)) add("error", "La columna " + op.newName + " ya existe en " + s + "." + op.table);
                    List<String> deps = dependents(s, op.table);
                    if (!deps.isEmpty()) add("warning", "Revisa si estos objetos usan la columna: " + String.join(", ", deps));
                    setCol(s, op.table, op.oldName, false);
                    setCol(s, op.table, op.newName, true);
                }
            }
            case "CONSTRAINT" -> {
                String kind = constraint(s, op.oldName);
                if (kind == null) add("error", "No existe la restricción " + s + "." + op.oldName);
                if (constraint(s, op.newName) != null) add("error", "Ya existe una restricción llamada " + s + "." + op.newName);
                constraints.put(k(s, op.oldName), null);
                constraints.put(k(s, op.newName), kind);
            }
            default -> {
                if (!"INDEX".equals(objType(s, op.oldName))) add("error", "No existe el índice " + s + "." + op.oldName);
                if (objType(s, op.newName) != null) add("error", "Ya existe un objeto llamado " + s + "." + op.newName);
                objects.put(k(s, op.oldName), null);
                objects.put(k(s, op.newName), "INDEX");
            }
        }
    }

    private void setComment(SetComment op, String s) {
        String t = objType(s, op.table);
        if (!Set.of("TABLE", "VIEW", "MATERIALIZED VIEW").contains(Objects.toString(t, "")))
            add("error", "No existe la tabla o vista " + s + "." + op.table);
        else if (op.column != null && !colExists(s, op.table, op.column))
            add("error", "La columna " + op.column + " no existe en " + s + "." + op.table);
    }

    private void dropConstraint(DropConstraint op, String s) {
        if (!requireTable(s, op.table)) return;
        String kind = constraint(s, op.constraintName);
        if (kind == null) {
            add("error", "No existe la restricción " + s + "." + op.constraintName);
            return;
        }
        if ((kind.equals("P") || kind.equals("U")) && !op.cascade) {
            List<String> refs = db.q("""
                    SELECT owner || '.' || table_name AS t FROM dba_constraints
                     WHERE constraint_type = 'R' AND r_owner = :o AND r_constraint_name = :n""", "o", s, "n", op.constraintName)
                    .stream().map(r -> (String) r.get("t")).toList();
            if (!refs.isEmpty()) add("error", "Llaves foráneas dependen de ella (" + String.join(", ", refs) + "); usa CASCADE");
        }
        constraints.put(k(s, op.constraintName), null);
        if (kind.equals("P")) pkTables.put(k(s, op.table), false);
    }

    private void createIndex(CreateIndex op, String s) {
        if (!requireTable(s, op.table)) return;
        if (objType(s, op.indexName) != null) add("error", "Ya existe un objeto llamado " + s + "." + op.indexName);
        List<String> plain = op.columns.stream().filter(IndexColumn::isColumn).map(c -> c.expression).toList();
        requireCols(s, op.table, plain, "");
        if (op.local && partitioning(s, op.table) == null && !objects.containsKey(k(s, op.table)))
            add("error", "LOCAL requiere que " + s + "." + op.table + " esté particionada");
        if (op.indexType.equals("DOMAIN")) {
            int dot = op.indextype.lastIndexOf('.');
            String owner = dot < 0 ? null : op.indextype.substring(0, dot);
            String name = op.indextype.substring(dot + 1);
            if (!exists("SELECT 1 FROM dba_indextypes WHERE owner = NVL(:o, owner) AND indextype_name = :n", "o", owner, "n", name))
                add("error", "El indextype " + op.indextype + " no está instalado en esta base");
        }
        if (op.compression != null && op.compression.startsWith("ADVANCED"))
            add("info", "COMPRESS ADVANCED requiere licencia de Advanced Compression en Enterprise Edition");
        if (!plain.isEmpty() && plain.size() == op.columns.size() && op.columns.stream().noneMatch(c -> c.descending)) {
            List<Map<String, Object>> same = db.q("""
                    SELECT index_name FROM dba_indexes i WHERE table_owner = :o AND table_name = :t
                       AND (SELECT LISTAGG(column_name, ',') WITHIN GROUP (ORDER BY column_position)
                              FROM dba_ind_columns c WHERE c.index_owner = i.owner AND c.index_name = i.index_name) = :cols""",
                    "o", s, "t", op.table, "cols", String.join(",", plain));
            if (!same.isEmpty()) add("error", "Ya existe un índice con las mismas columnas: " + same.get(0).get("index_name"));
        }
        objects.put(k(s, op.indexName), "INDEX");
    }

    private void dropIndex(DropIndex op, String s) {
        if (!"INDEX".equals(objType(s, op.indexName))) {
            add("error", "No existe el índice " + s + "." + op.indexName + later(futureObjs, k(s, op.indexName)));
            return;
        }
        List<Map<String, Object>> enforced = db.q("SELECT constraint_name FROM dba_constraints WHERE index_owner = :o AND index_name = :n",
                "o", s, "n", op.indexName);
        if (!enforced.isEmpty())
            add("error", "El índice soporta la restricción " + enforced.get(0).get("constraint_name") + "; elimínala o deshabilítala primero");
        objects.put(k(s, op.indexName), null);
    }

    private void alterIndex(AlterIndex op, String s) {
        if (!"INDEX".equals(objType(s, op.indexName))) {
            add("error", "No existe el índice " + s + "." + op.indexName);
            return;
        }
        if (op.action.equals("REBUILD") && exists("SELECT 1 FROM dba_indexes WHERE owner = :o AND index_name = :n AND partitioned = 'YES'",
                "o", s, "n", op.indexName))
            add("error", "Un índice particionado se reconstruye por partición; usa SQL personalizado (REBUILD PARTITION)");
    }

    private void addPartition(AddPartition op, String s) {
        if (!requireTable(s, op.table)) return;
        Map<String, Object> pt = partitioning(s, op.table);
        if (pt == null) {
            add("error", s + "." + op.table + " no está particionada");
            return;
        }
        if (!op.method.equals(pt.get("partitioning_type")))
            add("error", "La tabla está particionada por " + pt.get("partitioning_type") + ", no por " + op.method);
        if (pt.get("interval") != null) add("error", "La tabla usa INTERVAL: las particiones se crean automáticamente");
        List<Map<String, Object>> parts = db.q("SELECT partition_name, high_value FROM dba_tab_partitions WHERE table_owner = :o AND table_name = :t",
                "o", s, "t", op.table);
        if (parts.stream().anyMatch(p -> op.partition.name.equals(p.get("partition_name")))) add("error", "Ya existe la partición " + op.partition.name);
        if (op.method.equals("RANGE") && parts.stream().anyMatch(p -> Objects.toString(p.get("high_value"), "").contains("MAXVALUE")))
            add("error", "Existe una partición MAXVALUE; hay que dividirla (SPLIT) en lugar de agregar");
        if (op.method.equals("LIST") && parts.stream().anyMatch(p -> Objects.toString(p.get("high_value"), "").strip().equalsIgnoreCase("DEFAULT")))
            add("error", "Existe una partición DEFAULT; hay que dividirla (SPLIT) en lugar de agregar");
        if (op.method.equals("RANGE")) add("info", "El límite nuevo debe ser mayor al de la última partición");
    }

    private void dropPartition(DropPartition op, String s) {
        if (!requireTable(s, op.table)) return;
        List<String> parts = db.q("SELECT partition_name FROM dba_tab_partitions WHERE table_owner = :o AND table_name = :t", "o", s, "t", op.table)
                .stream().map(p -> (String) p.get("partition_name")).toList();
        if (!parts.contains(op.partitionName)) add("error", "No existe la partición " + op.partitionName + " en " + s + "." + op.table);
        else if (parts.size() == 1) add("error", "No se puede eliminar la única partición de la tabla");
        add("warning", "Se eliminan los datos de la partición");
    }

    private void createMvLog(CreateMvLog op, String s) {
        if (!requireTable(s, op.table)) return;
        if (exists("SELECT 1 FROM dba_mview_logs WHERE log_owner = :o AND master = :t", "o", s, "t", op.table))
            add("error", s + "." + op.table + " ya tiene log de vista materializada");
        if (op.withPrimaryKey && !hasPk(s, op.table)) add("error", "WITH PRIMARY KEY requiere que la tabla tenga llave primaria");
        requireCols(s, op.table, op.columns, "");
    }

    private void grant(Grant op, String s) {
        if (objType(s, op.object) == null) add("error", "No existe el objeto " + s + "." + op.object + later(futureObjs, k(s, op.object)));
        if (!userExists(op.grantee)) {
            String hint = later(futureUsers, op.grantee);
            add("error", "El usuario/rol " + op.grantee + " no existe" + (hint.isEmpty() ? " (agrega una operación Crear usuario antes)" : hint));
        }
        if (!op.columns.isEmpty()) requireCols(s, op.object, op.columns, "");
    }

    private void createSynonym(CreateSynonym op, String s) {
        if (objType(op.targetSchema, op.targetObject) == null) {
            String hint = later(futureObjs, k(op.targetSchema, op.targetObject));
            add(hint.isEmpty() ? "warning" : "error", "El objeto destino " + op.targetSchema + "." + op.targetObject + " no existe todavía" + hint);
        }
        String existing = objType(s, op.synonym);
        if (existing != null && !op.replace) add("error", "Ya existe " + s + "." + op.synonym + "; marca 'reemplazar' o usa otro nombre");
        else if (existing != null && !existing.equals("SYNONYM")) add("error", s + "." + op.synonym + " existe y no es sinónimo (" + existing + ")");
        else if (existing != null) add("info", "Se reemplazará el sinónimo existente " + s + "." + op.synonym);
        objects.put(k(s, op.synonym), "SYNONYM");
    }

    private void createCode(CreateOrReplaceCode op, String s) {
        String existing = objType(s, op.name);
        String kind = op.kind.equals("PACKAGE BODY") ? "PACKAGE" : op.kind;
        if (op.kind.equals("MATERIALIZED VIEW")) {
            if (existing != null) add("error", "Ya existe " + s + "." + op.name + "; una vista materializada se elimina y se vuelve a crear");
            boolean privs = exists("""
                    SELECT 1 FROM dba_sys_privs WHERE grantee = :o AND privilege IN ('CREATE TABLE','CREATE ANY TABLE')
                    UNION ALL SELECT 1 FROM dba_role_privs WHERE grantee = :o AND granted_role IN ('RESOURCE','DBA')""", "o", s);
            if (!privs && !users.contains(s)) add("error", s + " necesita CREATE TABLE para ser dueño de una vista materializada");
        } else if (existing != null && !existing.equals(kind)) {
            add("error", s + "." + op.name + " ya existe como " + existing);
        } else if (existing != null) {
            add("info", "Se reemplazará " + existing + " " + s + "." + op.name + " existente");
        }
        objects.put(k(s, op.name), kind);
    }

    private void dropCode(DropCode op, String s) {
        String kind = op.kind.equals("PACKAGE BODY") ? "PACKAGE" : op.kind;
        boolean exists = objects.containsKey(k(s, op.name)) ? kind.equals(objects.get(k(s, op.name)))
                : exists("SELECT 1 FROM dba_objects WHERE owner = :o AND object_name = :n AND object_type = :t", "o", s, "n", op.name, "t", op.kind);
        if (!exists) add("error", "No existe " + op.kind + " " + s + "." + op.name);
        List<String> deps = dependents(s, op.name);
        if (!deps.isEmpty()) add("warning", "Objetos dependientes quedarán inválidos: " + String.join(", ", deps));
        if (!op.kind.equals("PACKAGE BODY")) objects.put(k(s, op.name), null);
    }
}
