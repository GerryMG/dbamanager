"""Validación previa de operaciones contra el diccionario de Oracle (no ejecuta nada).

Simula el efecto de las operaciones anteriores de la misma solicitud (objetos, columnas,
restricciones e índices creados o eliminados) para que una tabla nueva y su índice, por ejemplo,
se validen juntos.
"""
from app.engines.base import Finding

_CONS_KIND = {"PRIMARY_KEY": "P", "UNIQUE": "U", "FOREIGN_KEY": "R", "CHECK": "C"}


class Preflight:
    def __init__(self, query):
        self.q = query  # q(sql, **binds) -> list[dict]
        self.findings: list[Finding] = []
        self.seq = 0
        self.users: set[str] = set()
        self.objects: dict[tuple[str, str], str | None] = {}  # (owner, name) -> tipo (None = eliminado)
        self.columns: dict[tuple[str, str], dict[str, bool]] = {}  # (owner, tabla) -> {col: existe}
        self.constraints: dict[tuple[str, str], str | None] = {}  # (owner, nombre) -> tipo P/U/R/C
        self.pk_tables: dict[tuple[str, str], bool] = {}
        self.read_only: dict[tuple[str, str], bool] = {}

    # ---------------------------------------------------------------- consultas con simulación
    def add(self, level: str, msg: str) -> None:
        self.findings.append(Finding(level, msg, self.seq))

    def user_exists(self, u: str) -> bool:
        return u == "PUBLIC" or u in self.users or bool(self.q(
            "SELECT 1 FROM dba_users WHERE username = :u UNION ALL SELECT 1 FROM dba_roles WHERE role = :u", u=u))

    def obj_type(self, owner: str, name: str) -> str | None:
        if (owner, name) in self.objects:
            return self.objects[(owner, name)]
        rows = self.q("""SELECT object_type FROM dba_objects WHERE owner = :o AND object_name = :n
                          AND object_type NOT IN ('PACKAGE BODY','TYPE BODY','TABLE PARTITION','INDEX PARTITION',
                                                  'TABLE SUBPARTITION','INDEX SUBPARTITION','LOB','LOB PARTITION')
                        ORDER BY DECODE(object_type,'MATERIALIZED VIEW',0,'TABLE',1,'INDEX',2,3)""", o=owner, n=name)
        return rows[0]["object_type"] if rows else None

    def is_table(self, owner: str, name: str) -> bool:
        return self.obj_type(owner, name) == "TABLE"

    def require_table(self, owner: str, table: str) -> bool:
        if not self.is_table(owner, table):
            self.add("error", f"No existe la tabla {owner}.{table}{self.later('obj', (owner, table))}")
            return False
        return True

    def is_read_only(self, owner: str, table: str) -> bool:
        if (owner, table) in self.read_only:
            return self.read_only[(owner, table)]
        return bool(self.q("SELECT 1 FROM dba_tables WHERE owner = :o AND table_name = :t AND read_only = 'YES'",
                           o=owner, t=table))

    _WRITES = {"ADD_COLUMN", "MODIFY_COLUMN", "DROP_COLUMN", "DROP_PARTITION", "ADD_CONSTRAINT", "CREATE_MV_LOG"}

    def col_exists(self, owner: str, table: str, col: str) -> bool:
        cols = self.columns.get((owner, table), {})
        if col in cols:
            return cols[col]
        return bool(self.q("SELECT 1 FROM dba_tab_cols WHERE owner = :o AND table_name = :t AND column_name = :c",
                           o=owner, t=table, c=col))

    def set_col(self, owner: str, table: str, col: str, exists: bool) -> None:
        self.columns.setdefault((owner, table), {})[col] = exists

    def require_cols(self, owner: str, table: str, cols: list[str], what: str = "") -> None:
        missing = [c for c in cols if not self.col_exists(owner, table, c)]
        if missing:
            hint = next((h for h in (self.later("col", (owner, table, c)) for c in missing) if h), "")
            self.add("error", f"{what}columna(s) inexistente(s) en {owner}.{table}: {', '.join(missing)}{hint}")

    def constraint(self, owner: str, name: str) -> dict | None:
        if (owner, name) in self.constraints:
            kind = self.constraints[(owner, name)]
            return {"constraint_type": kind, "table_name": None} if kind else None
        rows = self.q("SELECT constraint_type, table_name FROM dba_constraints WHERE owner = :o AND constraint_name = :n",
                      o=owner, n=name)
        return rows[0] if rows else None

    def has_pk(self, owner: str, table: str) -> bool:
        if (owner, table) in self.pk_tables:
            return self.pk_tables[(owner, table)]
        return bool(self.q("SELECT 1 FROM dba_constraints WHERE owner = :o AND table_name = :t AND constraint_type = 'P'",
                           o=owner, t=table))

    def key_columns(self, owner: str, table: str) -> list[set[str]]:
        rows = self.q("""SELECT c.constraint_name, cc.column_name FROM dba_constraints c
                           JOIN dba_cons_columns cc ON cc.owner = c.owner AND cc.constraint_name = c.constraint_name
                          WHERE c.owner = :o AND c.table_name = :t AND c.constraint_type IN ('P','U')""", o=owner, t=table)
        keys: dict[str, set[str]] = {}
        for r in rows:
            keys.setdefault(r["constraint_name"], set()).add(r["column_name"])
        return list(keys.values())

    def dependents(self, owner: str, name: str) -> list[str]:
        return [d["d"] for d in self.q("""SELECT owner || '.' || name || ' (' || LOWER(type) || ')' AS d FROM dba_dependencies
                                           WHERE referenced_owner = :o AND referenced_name = :n
                                             AND NOT (owner = :o AND name = :n) FETCH FIRST 20 ROWS ONLY""", o=owner, n=name)]

    def partitioning(self, owner: str, table: str) -> dict | None:
        rows = self.q("""SELECT partitioning_type, interval, partition_count FROM dba_part_tables
                          WHERE owner = :o AND table_name = :t""", o=owner, t=table)
        return rows[0] if rows else None

    # ---------------------------------------------------------------- reglas comunes
    def check_fk(self, owner: str, c) -> None:
        ref_owner = c.ref_schema or owner
        if not self.is_table(ref_owner, c.ref_table):
            self.add("error", f"La tabla referenciada {ref_owner}.{c.ref_table} no existe{self.later('obj', (ref_owner, c.ref_table))}")
            return
        if c.ref_columns:
            self.require_cols(ref_owner, c.ref_table, c.ref_columns, "FK: ")
            if (ref_owner, c.ref_table) not in self.pk_tables and set(c.ref_columns) not in self.key_columns(ref_owner, c.ref_table):
                self.add("warning", f"Las columnas referenciadas de {ref_owner}.{c.ref_table} deben ser su PK o una llave única")
        elif not self.has_pk(ref_owner, c.ref_table):
            self.add("error", f"{ref_owner}.{c.ref_table} no tiene llave primaria; indica las columnas referenciadas")
        if ref_owner != owner:
            self.add("info", f"El dueño {owner} necesita REFERENCES sobre {ref_owner}.{c.ref_table} (si no lo tiene, agrega un GRANT REFERENCES antes)")

    def check_constraint(self, owner: str, table: str, c, new_table: bool) -> None:
        if c.name and self.constraint(owner, c.name):
            self.add("error", f"Ya existe una restricción llamada {owner}.{c.name}")
        if c.columns:
            self.require_cols(owner, table, c.columns)
        if c.kind == "FOREIGN_KEY":
            self.check_fk(owner, c)
        if c.kind == "PRIMARY_KEY" and self.has_pk(owner, table):
            self.add("error", f"{owner}.{table} ya tiene llave primaria")
        if not new_table and c.enabled and c.validated:
            self.add("info", "Si hay renglones que no cumplan la restricción, fallará (usa NOVALIDATE para no revisar los existentes)")
        if c.name:
            self.constraints[(owner, c.name)] = _CONS_KIND[c.kind]
        if c.kind == "PRIMARY_KEY":
            self.pk_tables[(owner, table)] = True

    # ---------------------------------------------------------------- por operación
    def _scan_future(self, ops: list) -> None:
        """Qué crea cada operación, para detectar dependencias puestas después de quien las usa."""
        self.future_objs: dict[tuple[str, str], int] = {}
        self.future_users: dict[str, int] = {}
        self.future_cols: dict[tuple[str, str, str], int] = {}
        for seq, op in enumerate(ops, start=1):
            s, t = op.schema_name, op.type
            obj = {"CREATE_TABLE": "table", "CREATE_INDEX": "index_name", "CREATE_SEQUENCE": "name",
                   "CREATE_OR_REPLACE_CODE": "name", "CREATE_SYNONYM": "synonym"}.get(t)
            if obj:
                self.future_objs.setdefault((s, getattr(op, obj)), seq)
            if t == "RENAME" and op.target in ("TABLE", "INDEX"):
                self.future_objs.setdefault((s, op.new_name), seq)
            if t == "CREATE_USER":
                self.future_users.setdefault(s, seq)
            if t == "CREATE_TABLE":
                for c in op.columns:
                    self.future_cols.setdefault((s, op.table, c.name), seq)
            if t == "ADD_COLUMN":
                self.future_cols.setdefault((s, op.table, op.column.name), seq)
            if t == "RENAME" and op.target == "COLUMN":
                self.future_cols.setdefault((s, op.table, op.new_name), seq)

    def later(self, kind: str, key) -> str:
        """Texto adicional si lo que falta se crea en una operación posterior."""
        seq = {"obj": self.future_objs, "user": self.future_users, "col": self.future_cols}[kind].get(key)
        if seq and seq > self.seq:
            return (f" — se crea más adelante, en la operación #{seq}. Las operaciones se ejecutan en el orden "
                    f"mostrado: mueve la #{seq} antes de la #{self.seq}")
        return ""

    def run(self, ops: list) -> list[Finding]:
        self._scan_future(ops)
        from app.services.guard import op_schemas

        for self.seq, op in enumerate(ops, start=1):
            internal = [x for x in sorted(op_schemas(op)) if x != "PUBLIC" and self.q(
                "SELECT 1 FROM dba_users WHERE username = :u AND oracle_maintained = 'Y'", u=x)]
            if internal:
                self.add("error", f"Esquema interno de Oracle: {', '.join(internal)}; no se permiten cambios ahí")
            s = op.schema_name
            if op.type != "CREATE_USER" and not self.user_exists(s):
                self.add("error", f"El esquema/usuario {s} no existe en esta base{self.later('user', s)}")
            if op.type in self._WRITES and self.is_table(s, op.table) and self.is_read_only(s, op.table):
                self.add("error", f"{s}.{op.table} está en solo lectura (READ ONLY); ponla en READ WRITE antes")
            handler = getattr(self, f"op_{op.type.lower()}", None)
            if handler:
                handler(op, s)
        return self.findings

    def op_create_table(self, op, s):
        if self.obj_type(s, op.table):
            self.add("error", f"Ya existe un objeto llamado {s}.{op.table}")
        self.objects[(s, op.table)] = "TABLE"
        for c in op.columns:
            self.set_col(s, op.table, c.name, True)
        if op.primary_key:
            if op.primary_key_name and self.constraint(s, op.primary_key_name):
                self.add("error", f"Ya existe una restricción llamada {s}.{op.primary_key_name}")
            self.pk_tables[(s, op.table)] = True
            if op.primary_key_name:
                self.constraints[(s, op.primary_key_name)] = "P"
        for c in op.constraints:
            self.check_constraint(s, op.table, c, new_table=True)
        if op.compression == "ADVANCED":
            self.add("info", "ROW STORE COMPRESS ADVANCED requiere licencia de Advanced Compression en Enterprise Edition")
        if not op.primary_key and not op.temporary:
            self.add("warning", "La tabla no tiene llave primaria")

    def op_add_column(self, op, s):
        if self.require_table(s, op.table):
            if self.col_exists(s, op.table, op.column.name):
                self.add("error", f"La columna {op.column.name} ya existe en {s}.{op.table}")
            c = op.column
            if not c.nullable and c.default is None and not c.identity and not c.virtual_expression:
                self.add("warning", f"Agregar {c.name} NOT NULL sin DEFAULT falla si la tabla tiene renglones")
            if c.identity and self.q("SELECT 1 FROM dba_tab_identity_cols WHERE owner = :o AND table_name = :t",
                                     o=s, t=op.table):
                self.add("error", f"{s}.{op.table} ya tiene una columna identidad (solo se permite una)")
        self.set_col(s, op.table, op.column.name, True)

    def op_modify_column(self, op, s):
        if self.require_table(s, op.table):
            if not self.col_exists(s, op.table, op.column.name):
                self.add("error", f"La columna {op.column.name} no existe en {s}.{op.table}")
            prev, c = op.previous, op.column
            if prev and prev.length and c.length and c.length < prev.length:
                self.add("warning", f"Reducir {c.name} de {prev.length} a {c.length} falla si hay valores más largos")
            if prev and prev.nullable and not c.nullable:
                self.add("info", f"Poner {c.name} NOT NULL falla si existen valores nulos")
            if prev and prev.data_type != c.data_type:
                self.add("warning", f"Cambiar el tipo de {c.name} ({prev.data_type} → {c.data_type}) suele requerir la columna vacía")

    def op_drop_column(self, op, s):
        if self.require_table(s, op.table):
            if not self.col_exists(s, op.table, op.column_name):
                self.add("error", f"La columna {op.column_name} no existe en {s}.{op.table}")
            used = self.q("""SELECT 'restricción ' || constraint_name AS u FROM dba_cons_columns
                              WHERE owner = :o AND table_name = :t AND column_name = :c
                             UNION ALL
                             SELECT 'índice ' || index_name FROM dba_ind_columns
                              WHERE table_owner = :o AND table_name = :t AND column_name = :c""",
                          o=s, t=op.table, c=op.column_name)
            if used:
                self.add("warning", "La columna se usa en: " + ", ".join(u["u"] for u in used))
        self.set_col(s, op.table, op.column_name, False)

    def op_drop_table(self, op, s):
        if not self.require_table(s, op.table):
            return
        refs = self.q("""SELECT c.owner || '.' || c.table_name AS t FROM dba_constraints c
                           JOIN dba_constraints p ON p.owner = c.r_owner AND p.constraint_name = c.r_constraint_name
                          WHERE c.constraint_type = 'R' AND p.owner = :o AND p.table_name = :n
                            AND NOT (c.owner = :o AND c.table_name = :n)""", o=s, n=op.table)
        if refs and not op.cascade_constraints:
            self.add("error", "Otras tablas la referencian (" + ", ".join(r["t"] for r in refs) + "); se requiere CASCADE CONSTRAINTS")
        elif refs:
            self.add("warning", "Se eliminarán llaves foráneas de: " + ", ".join(r["t"] for r in refs))
        deps = self.dependents(s, op.table)
        if deps:
            self.add("warning", "Quedarán inválidos: " + ", ".join(deps))
        if not op.purge:
            self.add("info", "Sin PURGE la tabla queda en la papelera (recuperable con FLASHBACK)")
        self.objects[(s, op.table)] = None

    def op_alter_table_options(self, op, s):
        self.require_table(s, op.table)
        if op.read_only is not None:
            self.read_only[(s, op.table)] = op.read_only
        if op.compression == "ADVANCED":
            self.add("info", "ROW STORE COMPRESS ADVANCED requiere licencia de Advanced Compression en Enterprise Edition")
        if op.compression:
            self.add("info", "La compresión aplica a los datos que se carguen después; los existentes no se recomprimen")

    def op_rename(self, op, s):
        if op.target == "TABLE":
            if self.require_table(s, op.old_name):
                if self.obj_type(s, op.new_name):
                    self.add("error", f"Ya existe un objeto llamado {s}.{op.new_name}")
                deps = self.dependents(s, op.old_name)
                if deps:
                    self.add("warning", "Quedarán inválidos (usan el nombre anterior): " + ", ".join(deps))
                self.objects[(s, op.old_name)] = None
                self.objects[(s, op.new_name)] = "TABLE"
        elif op.target == "COLUMN":
            if self.require_table(s, op.table):
                if not self.col_exists(s, op.table, op.old_name):
                    self.add("error", f"La columna {op.old_name} no existe en {s}.{op.table}")
                if self.col_exists(s, op.table, op.new_name):
                    self.add("error", f"La columna {op.new_name} ya existe en {s}.{op.table}")
                deps = self.dependents(s, op.table)
                if deps:
                    self.add("warning", "Revisa si estos objetos usan la columna: " + ", ".join(deps))
                self.set_col(s, op.table, op.old_name, False)
                self.set_col(s, op.table, op.new_name, True)
        elif op.target == "CONSTRAINT":
            if not self.constraint(s, op.old_name):
                self.add("error", f"No existe la restricción {s}.{op.old_name}")
            if self.constraint(s, op.new_name):
                self.add("error", f"Ya existe una restricción llamada {s}.{op.new_name}")
            kind = (self.constraint(s, op.old_name) or {}).get("constraint_type")
            self.constraints[(s, op.old_name)] = None
            self.constraints[(s, op.new_name)] = kind
        else:
            if self.obj_type(s, op.old_name) != "INDEX":
                self.add("error", f"No existe el índice {s}.{op.old_name}")
            if self.obj_type(s, op.new_name):
                self.add("error", f"Ya existe un objeto llamado {s}.{op.new_name}")
            self.objects[(s, op.old_name)] = None
            self.objects[(s, op.new_name)] = "INDEX"

    def op_set_comment(self, op, s):
        if self.obj_type(s, op.table) not in ("TABLE", "VIEW", "MATERIALIZED VIEW"):
            self.add("error", f"No existe la tabla o vista {s}.{op.table}")
        elif op.column and not self.col_exists(s, op.table, op.column):
            self.add("error", f"La columna {op.column} no existe en {s}.{op.table}")

    def op_add_constraint(self, op, s):
        if self.require_table(s, op.table):
            self.check_constraint(s, op.table, op.constraint, new_table=False)

    def op_drop_constraint(self, op, s):
        if not self.require_table(s, op.table):
            return
        con = self.constraint(s, op.constraint_name)
        if not con:
            self.add("error", f"No existe la restricción {s}.{op.constraint_name}")
            return
        if con["constraint_type"] in ("P", "U") and not op.cascade:
            refs = self.q("""SELECT owner || '.' || table_name AS t FROM dba_constraints
                              WHERE constraint_type = 'R' AND r_owner = :o AND r_constraint_name = :n""",
                          o=s, n=op.constraint_name)
            if refs:
                self.add("error", "Llaves foráneas dependen de ella (" + ", ".join(r["t"] for r in refs) + "); usa CASCADE")
        self.constraints[(s, op.constraint_name)] = None
        if con["constraint_type"] == "P":
            self.pk_tables[(s, op.table)] = False

    def op_constraint_state(self, op, s):
        if self.require_table(s, op.table) and not self.constraint(s, op.constraint_name):
            self.add("error", f"No existe la restricción {s}.{op.constraint_name}")
        if op.action == "DISABLE":
            self.add("warning", "Mientras esté deshabilitada, la base no validará la regla")

    def op_create_index(self, op, s):
        if not self.require_table(s, op.table):
            return
        if self.obj_type(s, op.index_name):
            self.add("error", f"Ya existe un objeto llamado {s}.{op.index_name}")
        plain = [c.expression for c in op.columns if c.is_column]
        self.require_cols(s, op.table, plain)
        if op.local and not self.partitioning(s, op.table) and (s, op.table) not in self.objects:
            self.add("error", f"LOCAL requiere que {s}.{op.table} esté particionada")
        if op.index_type == "DOMAIN":
            owner, _, name = op.indextype.rpartition(".")
            if not self.q("SELECT 1 FROM dba_indextypes WHERE owner = NVL(:o, owner) AND indextype_name = :n",
                          o=owner or None, n=name):
                self.add("error", f"El indextype {op.indextype} no está instalado en esta base")
        if op.compression and op.compression.startswith("ADVANCED"):
            self.add("info", "COMPRESS ADVANCED requiere licencia de Advanced Compression en Enterprise Edition")
        if plain and len(plain) == len(op.columns) and not any(c.descending for c in op.columns):
            same = self.q("""SELECT index_name FROM dba_indexes i WHERE table_owner = :o AND table_name = :t
                               AND (SELECT LISTAGG(column_name, ',') WITHIN GROUP (ORDER BY column_position)
                                      FROM dba_ind_columns c WHERE c.index_owner = i.owner AND c.index_name = i.index_name) = :cols""",
                          o=s, t=op.table, cols=",".join(plain))
            if same:
                self.add("error", f"Ya existe un índice con las mismas columnas: {same[0]['index_name']}")
        self.objects[(s, op.index_name)] = "INDEX"

    def op_drop_index(self, op, s):
        if self.obj_type(s, op.index_name) != "INDEX":
            self.add("error", f"No existe el índice {s}.{op.index_name}{self.later('obj', (s, op.index_name))}")
            return
        enforced = self.q("SELECT constraint_name FROM dba_constraints WHERE index_owner = :o AND index_name = :n",
                          o=s, n=op.index_name)
        if enforced:
            self.add("error", f"El índice soporta la restricción {enforced[0]['constraint_name']}; elimínala o deshabilítala primero")
        self.objects[(s, op.index_name)] = None

    def op_alter_index(self, op, s):
        if self.obj_type(s, op.index_name) != "INDEX":
            self.add("error", f"No existe el índice {s}.{op.index_name}")
            return
        if op.action == "REBUILD" and self.q("SELECT 1 FROM dba_indexes WHERE owner = :o AND index_name = :n AND partitioned = 'YES'",
                                             o=s, n=op.index_name):
            self.add("error", "Un índice particionado se reconstruye por partición; usa SQL personalizado (REBUILD PARTITION)")

    def op_add_partition(self, op, s):
        if not self.require_table(s, op.table):
            return
        pt = self.partitioning(s, op.table)
        if not pt:
            self.add("error", f"{s}.{op.table} no está particionada")
            return
        if pt["partitioning_type"] != op.method:
            self.add("error", f"La tabla está particionada por {pt['partitioning_type']}, no por {op.method}")
        if pt["interval"]:
            self.add("error", "La tabla usa INTERVAL: las particiones se crean automáticamente")
        parts = self.q("SELECT partition_name, high_value FROM dba_tab_partitions WHERE table_owner = :o AND table_name = :t",
                       o=s, t=op.table)
        if any(p["partition_name"] == op.partition.name for p in parts):
            self.add("error", f"Ya existe la partición {op.partition.name}")
        if op.method == "RANGE" and any("MAXVALUE" in (p["high_value"] or "") for p in parts):
            self.add("error", "Existe una partición MAXVALUE; hay que dividirla (SPLIT) en lugar de agregar")
        if op.method == "LIST" and any((p["high_value"] or "").strip().upper() == "DEFAULT" for p in parts):
            self.add("error", "Existe una partición DEFAULT; hay que dividirla (SPLIT) en lugar de agregar")
        if op.method == "RANGE":
            self.add("info", "El límite nuevo debe ser mayor al de la última partición")

    def op_drop_partition(self, op, s):
        if not self.require_table(s, op.table):
            return
        parts = [p["partition_name"] for p in self.q(
            "SELECT partition_name FROM dba_tab_partitions WHERE table_owner = :o AND table_name = :t", o=s, t=op.table)]
        if op.partition_name not in parts:
            self.add("error", f"No existe la partición {op.partition_name} en {s}.{op.table}")
        elif len(parts) == 1:
            self.add("error", "No se puede eliminar la única partición de la tabla")
        self.add("warning", "Se eliminan los datos de la partición")

    def op_create_sequence(self, op, s):
        if self.obj_type(s, op.name):
            self.add("error", f"Ya existe un objeto llamado {s}.{op.name}")
        self.objects[(s, op.name)] = "SEQUENCE"

    def op_alter_sequence(self, op, s):
        if self.obj_type(s, op.name) != "SEQUENCE":
            self.add("error", f"No existe la secuencia {s}.{op.name}{self.later('obj', (s, op.name))}")
        if op.restart_with is not None:
            self.add("warning", "Reiniciar la secuencia puede generar valores duplicados si ya se usaron")

    def op_create_mv_log(self, op, s):
        if not self.require_table(s, op.table):
            return
        if self.q("SELECT 1 FROM dba_mview_logs WHERE log_owner = :o AND master = :t", o=s, t=op.table):
            self.add("error", f"{s}.{op.table} ya tiene log de vista materializada")
        if op.with_primary_key and not self.has_pk(s, op.table):
            self.add("error", "WITH PRIMARY KEY requiere que la tabla tenga llave primaria")
        self.require_cols(s, op.table, op.columns)

    def op_drop_mv_log(self, op, s):
        if self.require_table(s, op.table) and not self.q(
                "SELECT 1 FROM dba_mview_logs WHERE log_owner = :o AND master = :t", o=s, t=op.table):
            self.add("error", f"{s}.{op.table} no tiene log de vista materializada")
        self.add("warning", "Las vistas materializadas con refresco FAST sobre esta tabla dejarán de poder refrescarse rápido")

    def op_grant(self, op, s):
        if not self.obj_type(s, op.object):
            self.add("error", f"No existe el objeto {s}.{op.object}{self.later('obj', (s, op.object))}")
        if not self.user_exists(op.grantee):
            hint = self.later("user", op.grantee)
            self.add("error", f"El usuario/rol {op.grantee} no existe{hint or ' (agrega una operación Crear usuario antes)'}")
        if op.columns:
            self.require_cols(s, op.object, op.columns)

    op_revoke = op_grant

    def op_create_synonym(self, op, s):
        if not self.obj_type(op.target_schema, op.target_object):
            hint = self.later("obj", (op.target_schema, op.target_object))
            self.add("error" if hint else "warning",
                     f"El objeto destino {op.target_schema}.{op.target_object} no existe todavía{hint}")
        existing = self.obj_type(s, op.synonym)
        if existing and not op.replace:
            self.add("error", f"Ya existe {s}.{op.synonym}; marca 'reemplazar' o usa otro nombre")
        elif existing and existing != "SYNONYM":
            self.add("error", f"{s}.{op.synonym} existe y no es sinónimo ({existing})")
        elif existing:
            self.add("info", f"Se reemplazará el sinónimo existente {s}.{op.synonym}")
        self.objects[(s, op.synonym)] = "SYNONYM"

    def op_drop_synonym(self, op, s):
        if self.obj_type(s, op.synonym) != "SYNONYM":
            self.add("error", f"No existe el sinónimo {s}.{op.synonym}")
        self.objects[(s, op.synonym)] = None

    def op_create_user(self, op, s):
        if self.user_exists(s):
            self.add("error", f"El usuario {s} ya existe")
        self.users.add(s)
        self.add("info", "La contraseña se genera al ejecutar y se muestra una sola vez al DBA ejecutor")

    def op_create_or_replace_code(self, op, s):
        existing = self.obj_type(s, op.name)
        kind = "PACKAGE" if op.kind == "PACKAGE BODY" else op.kind
        if op.kind == "MATERIALIZED VIEW":
            if existing:
                self.add("error", f"Ya existe {s}.{op.name}; una vista materializada se elimina y se vuelve a crear")
            privs = self.q("""SELECT 1 FROM dba_sys_privs WHERE grantee = :o AND privilege IN ('CREATE TABLE','CREATE ANY TABLE')
                              UNION ALL SELECT 1 FROM dba_role_privs WHERE grantee = :o AND granted_role IN ('RESOURCE','DBA')""", o=s)
            if not privs and s not in self.users:
                self.add("error", f"{s} necesita CREATE TABLE para ser dueño de una vista materializada")
        elif existing and existing != kind:
            self.add("error", f"{s}.{op.name} ya existe como {existing}")
        elif existing:
            self.add("info", f"Se reemplazará {existing} {s}.{op.name} existente")
        self.objects[(s, op.name)] = kind

    def op_drop_code(self, op, s):
        kind = "PACKAGE" if op.kind == "PACKAGE BODY" else op.kind
        exists = (self.objects.get((s, op.name)) == kind) if (s, op.name) in self.objects else bool(self.q(
            "SELECT 1 FROM dba_objects WHERE owner = :o AND object_name = :n AND object_type = :t", o=s, n=op.name, t=op.kind))
        if not exists:
            self.add("error", f"No existe {op.kind} {s}.{op.name}")
        deps = self.dependents(s, op.name)
        if deps:
            self.add("warning", "Objetos dependientes quedarán inválidos: " + ", ".join(deps))
        if op.kind != "PACKAGE BODY":
            self.objects[(s, op.name)] = None

    def op_custom_sql(self, op, s):
        self.add("warning", "SQL personalizado: revisar manualmente cada sentencia antes de aprobar")
