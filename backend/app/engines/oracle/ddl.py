"""Traducción de operaciones a SQL de Oracle.

Todos los identificadores ya llegan validados (app.operations.ident), por eso se emiten sin
comillas. Los textos libres (comentarios) se escapan.
"""
from app.engines.base import Statement
from app import operations as ops

PASSWORD_PLACEHOLDER = "{{PASSWORD}}"

# "min_version": versión mínima de Oracle que tiene el tipo
DATA_TYPES = [
    {"name": "VARCHAR2", "length": True, "max_length": 4000, "default_length": 100},
    {"name": "NVARCHAR2", "length": True, "max_length": 2000, "default_length": 100},
    {"name": "CHAR", "length": True, "max_length": 2000, "default_length": 1},
    {"name": "NCHAR", "length": True, "max_length": 1000, "default_length": 1},
    {"name": "NUMBER", "precision": True, "scale": True},
    {"name": "INTEGER"},
    {"name": "FLOAT", "precision": True},
    {"name": "BINARY_FLOAT"},
    {"name": "BINARY_DOUBLE"},
    {"name": "DATE"},
    {"name": "TIMESTAMP", "precision": True},
    {"name": "TIMESTAMP WITH TIME ZONE", "precision": True},
    {"name": "TIMESTAMP WITH LOCAL TIME ZONE", "precision": True},
    {"name": "CLOB"},
    {"name": "NCLOB"},
    {"name": "BLOB"},
    {"name": "RAW", "length": True, "max_length": 2000, "default_length": 16},
    {"name": "JSON", "min_version": "21"},
    {"name": "BOOLEAN", "min_version": "23"},
]
_TYPES = {t["name"]: t for t in DATA_TYPES}


# ------------------------------------------------------------------ compatibilidad por versión (12.1 en adelante)
MIN_VERSION = (12, 1)
_IDENT_KEYS = {"schema", "table", "name", "column_name", "index_name", "constraint_name", "synonym", "object", "grantee",
               "target_schema", "target_object", "old_name", "new_name", "primary_key_name", "ref_schema", "ref_table",
               "partition_name", "tablespace", "index_tablespace", "primary_key_tablespace", "default_tablespace"}
_IDENT_LIST_KEYS = {"columns", "primary_key", "ref_columns", "roles"}


def parse_version(text: str | None) -> tuple[int, ...] | None:
    if not text:
        return None
    try:
        return tuple(int(x) for x in text.split(".")[:4])
    except ValueError:
        return None


def _vtxt(v: tuple[int, ...]) -> str:
    return ".".join(str(x) for x in v)


def _walk_identifiers(data, out: list[str]) -> None:
    if isinstance(data, dict):
        for k, v in data.items():
            if k in _IDENT_KEYS and isinstance(v, str):
                out.append(v)
            elif k in _IDENT_LIST_KEYS and isinstance(v, list):
                out += [x for x in v if isinstance(x, str)]
            else:
                _walk_identifiers(v, out)
    elif isinstance(data, list):
        for v in data:
            _walk_identifiers(v, out)


def check_version(op, version: tuple[int, ...] | None) -> None:
    """Rechaza sintaxis que la versión del servidor no soporta. Sin versión conocida no se restringe."""
    if version is None:
        return
    errors = []
    if version < MIN_VERSION:
        raise ValueError(f"DBA Manager requiere Oracle {_vtxt(MIN_VERSION)} o superior (servidor: {_vtxt(version)})")
    if version < (12, 2):
        names: list[str] = []
        _walk_identifiers(op.model_dump(by_alias=True), names)
        long_names = sorted({n for n in names if len(n.encode()) > 30 and n != "PUBLIC"})
        if long_names:
            errors.append(f"Oracle {_vtxt(version)} admite nombres de máximo 30 bytes: {', '.join(long_names)}")
    for c in getattr(op, "columns", []) or []:
        if isinstance(c, ops.Column):
            errors += _column_version_errors(c, version)
    if isinstance(getattr(op, "column", None), ops.Column):
        errors += _column_version_errors(op.column, version)
    t = op.type
    if t == "ALTER_SEQUENCE" and op.restart_with is not None and version < (18,):
        errors.append("ALTER SEQUENCE ... RESTART requiere Oracle 18c; en 12c hay que eliminar y recrear la secuencia")
    if t in ("GRANT", "REVOKE") and "READ" in op.privileges and version < (12, 1, 0, 2):
        errors.append("El privilegio READ requiere Oracle 12.1.0.2; usa SELECT")
    if t == "CREATE_TABLE" and op.partitioning and op.partitioning.method == "LIST" and len(op.partitioning.columns) > 1 and version < (12, 2):
        errors.append("El particionado LIST por varias columnas requiere Oracle 12.2")
    if t == "CREATE_INDEX" and op.compression == "ADVANCED_HIGH" and version < (12, 1, 0, 2):
        errors.append("COMPRESS ADVANCED HIGH requiere Oracle 12.1.0.2")
    if errors:
        raise ValueError("; ".join(errors))


def _column_version_errors(c: ops.Column, version: tuple[int, ...]) -> list[str]:
    errors = []
    if c.data_type == "BOOLEAN" and version < (23,):
        errors.append(f"{c.name}: el tipo BOOLEAN requiere Oracle 23ai (usa NUMBER(1) o CHAR(1) con CHECK)")
    if c.data_type == "JSON" and version < (21,):
        errors.append(f"{c.name}: el tipo JSON requiere Oracle 21c (usa CLOB o VARCHAR2 con CHECK ({c.name} IS JSON))")
    return errors


def q(text: str) -> str:
    return "'" + text.replace("'", "''") + "'"


def render_type(col: ops.Column) -> str:
    if col.data_type is None:
        return ""  # columna virtual: Oracle deduce el tipo de la expresión
    spec = _TYPES.get(col.data_type)
    if spec is None:
        raise ValueError(f"Tipo de dato no soportado en Oracle: {col.data_type}")
    name = col.data_type
    if spec.get("length"):
        if not col.length:
            raise ValueError(f"{col.name}: {name} requiere longitud")
        if col.length > spec["max_length"]:
            raise ValueError(f"{col.name}: longitud máxima de {name} es {spec['max_length']}")
        unit = " CHAR" if col.char_semantics and name in ("VARCHAR2", "CHAR") else ""
        return f"{name}({col.length}{unit})"
    if name == "NUMBER" and col.precision:
        if not 1 <= col.precision <= 38:
            raise ValueError(f"{col.name}: la precisión de NUMBER va de 1 a 38")
        return f"NUMBER({col.precision},{col.scale})" if col.scale is not None else f"NUMBER({col.precision})"
    if name == "NUMBER" and col.scale is not None:
        return f"NUMBER(*,{col.scale})"
    if name == "FLOAT" and col.precision:
        if not 1 <= col.precision <= 126:
            raise ValueError(f"{col.name}: la precisión de FLOAT va de 1 a 126")
        return f"FLOAT({col.precision})"
    if name.startswith("TIMESTAMP") and col.precision is not None:
        if not 0 <= col.precision <= 9:
            raise ValueError(f"{col.name}: la precisión de TIMESTAMP va de 0 a 9")
        return name.replace("TIMESTAMP", f"TIMESTAMP({col.precision})", 1)
    return name


_IDENTITY = {"ALWAYS": "GENERATED ALWAYS AS IDENTITY", "BY_DEFAULT": "GENERATED BY DEFAULT AS IDENTITY",
             "BY_DEFAULT_ON_NULL": "GENERATED BY DEFAULT ON NULL AS IDENTITY"}


def render_column(col: ops.Column) -> str:
    parts = [p for p in (col.name, render_type(col)) if p]
    if col.invisible:
        parts.append("INVISIBLE")
    if col.virtual_expression:
        parts.append(f"GENERATED ALWAYS AS ({col.virtual_expression}) VIRTUAL")
    elif col.identity:
        if col.data_type not in ("NUMBER", "INTEGER"):
            raise ValueError(f"{col.name}: solo columnas numéricas pueden ser identidad")
        opts = []
        if col.identity_start is not None:
            opts.append(f"START WITH {col.identity_start}")
        if col.identity_increment is not None:
            opts.append(f"INCREMENT BY {col.identity_increment}")
        parts.append(_IDENTITY[col.identity_mode] + (f" ({' '.join(opts)})" if opts else ""))
    elif col.default is not None:
        parts.append(f"DEFAULT {'ON NULL ' if col.default_on_null else ''}{col.default}")
    if not col.nullable:
        parts.append("NOT NULL")
    return " ".join(parts)


def render_constraint(c: ops.ConstraintDef, schema: str) -> str:
    out = f"CONSTRAINT {c.name} " if c.name else ""
    cols = ", ".join(c.columns)
    if c.kind == "PRIMARY_KEY":
        out += f"PRIMARY KEY ({cols})"
    elif c.kind == "UNIQUE":
        out += f"UNIQUE ({cols})"
    elif c.kind == "FOREIGN_KEY":
        out += f"FOREIGN KEY ({cols}) REFERENCES {c.ref_schema or schema}.{c.ref_table}"
        if c.ref_columns:
            out += f" ({', '.join(c.ref_columns)})"
        if c.on_delete == "CASCADE":
            out += " ON DELETE CASCADE"
        elif c.on_delete == "SET_NULL":
            out += " ON DELETE SET NULL"
    else:
        out += f"CHECK ({c.condition})"
    if c.index_tablespace and c.kind in ("PRIMARY_KEY", "UNIQUE"):
        out += f" USING INDEX TABLESPACE {c.index_tablespace}"
    if c.deferrable:
        out += " DEFERRABLE" + (" INITIALLY DEFERRED" if c.initially_deferred else "")
    if not c.enabled:
        out += " DISABLE"
    elif not c.validated:
        out += " ENABLE NOVALIDATE"
    return out


def _column_comment(schema: str, table: str, col: ops.Column) -> list[Statement]:
    if col.comment:
        return [Statement(f"COMMENT ON COLUMN {schema}.{table}.{col.name} IS {q(col.comment)}")]
    return []


def _constraint_name(prefix: str, table: str) -> str:
    return f"{prefix}_{table}"[:128]


_COMPRESSION = {"NONE": "NOCOMPRESS", "BASIC": "ROW STORE COMPRESS BASIC", "ADVANCED": "ROW STORE COMPRESS ADVANCED"}


def _partition(p: ops.PartitionDef, method: str) -> str:
    out = f"PARTITION {p.name}"
    if method == "RANGE":
        out += f" VALUES LESS THAN ({p.values})"
    elif method == "LIST":
        out += f" VALUES ({p.values})"
    if p.tablespace:
        out += f" TABLESPACE {p.tablespace}"
    return out


def render_partitioning(pt: ops.Partitioning) -> str:
    out = f"PARTITION BY {pt.method} ({', '.join(pt.columns)})"
    if pt.interval:
        out += f" INTERVAL ({pt.interval})"
    if pt.method == "HASH" and not pt.partitions:
        return out + f" PARTITIONS {pt.hash_count}"
    return out + " (\n  " + ",\n  ".join(_partition(p, pt.method) for p in pt.partitions) + "\n)"


def _split_custom_sql(sql: str) -> list[Statement]:
    from app.engines.oracle.sql_import import PLSQL_START, split_script, strip_comments

    out: list[Statement] = []
    for stmt in split_script(sql):
        text = stmt.strip()
        if PLSQL_START.match(strip_comments(text)):
            out.append(Statement(text.rstrip("/").strip(), plsql=True))
        else:
            out.append(Statement(text.rstrip(";").strip()))
    return out


def render(op, version: tuple[int, ...] | None = None) -> list[Statement]:  # noqa: C901 - un caso por tipo de operación
    check_version(op, version)
    t = op.type
    s = op.schema_
    tbl = f"{s}.{getattr(op, 'table', None)}"

    if t == "CREATE_TABLE":
        lines = [render_column(c) for c in op.columns]
        if op.primary_key:
            pk = f"CONSTRAINT {op.primary_key_name or _constraint_name('PK', op.table)} PRIMARY KEY ({', '.join(op.primary_key)})"
            if op.primary_key_tablespace:
                pk += f" USING INDEX TABLESPACE {op.primary_key_tablespace}"
            lines.append(pk)
        lines += [render_constraint(c, s) for c in op.constraints]
        body = ",\n  ".join(lines)
        kind = "GLOBAL TEMPORARY TABLE" if op.temporary else "TABLE"
        sql = f"CREATE {kind} {tbl} (\n  {body}\n)"
        if op.temporary:
            sql += f" ON COMMIT {'DELETE' if op.temporary == 'DELETE_ROWS' else 'PRESERVE'} ROWS"
        if op.tablespace:
            sql += f" TABLESPACE {op.tablespace}"
        if op.compression:
            sql += f" {_COMPRESSION[op.compression]}"
        if op.logging is not None:
            sql += " LOGGING" if op.logging else " NOLOGGING"
        if op.partitioning:
            sql += "\n" + render_partitioning(op.partitioning)
        st = [Statement(sql)]
        if op.comment:
            st.append(Statement(f"COMMENT ON TABLE {tbl} IS {q(op.comment)}"))
        for c in op.columns:
            st += _column_comment(s, op.table, c)
        return st

    if t == "ADD_COLUMN":
        return [Statement(f"ALTER TABLE {tbl} ADD ({render_column(op.column)})"),
                *_column_comment(s, op.table, op.column)]

    if t == "MODIFY_COLUMN":
        c, prev = op.column, op.previous
        if prev is not None and (c.virtual_expression or prev.virtual_expression) and c.virtual_expression != prev.virtual_expression:
            raise ValueError(f"{c.name}: cambiar la expresión de una columna virtual requiere eliminarla y crearla de nuevo")
        parts = []
        if render_type(c) and (prev is None or render_type(c) != render_type(prev)):
            parts.append(render_type(c))
        if not c.virtual_expression and not c.identity and (
                prev is None or (c.default or None) != (prev.default or None) or c.default_on_null != prev.default_on_null):
            parts.append(f"DEFAULT {'ON NULL ' if c.default_on_null else ''}{c.default}" if c.default is not None else "DEFAULT NULL")
        if prev is None or c.nullable != prev.nullable:
            parts.append("NULL" if c.nullable else "NOT NULL")
        st = []
        if parts:
            st.append(Statement(f"ALTER TABLE {tbl} MODIFY ({c.name} {' '.join(parts)})"))
        if prev is None and c.invisible or prev is not None and c.invisible != prev.invisible:
            # Oracle no permite combinar el cambio de visibilidad con otras modificaciones (ORA-54046)
            st.append(Statement(f"ALTER TABLE {tbl} MODIFY ({c.name} {'INVISIBLE' if c.invisible else 'VISIBLE'})"))
        if c.comment is not None and (prev is None or c.comment != prev.comment):
            st.append(Statement(f"COMMENT ON COLUMN {tbl}.{c.name} IS {q(c.comment)}"))
        if not st:
            raise ValueError(f"La columna {c.name} no tiene cambios respecto a su definición actual")
        return st

    if t == "DROP_COLUMN":
        return [Statement(f"ALTER TABLE {tbl} DROP COLUMN {op.column_name}")]

    if t == "DROP_TABLE":
        sql = f"DROP TABLE {tbl}"
        if op.cascade_constraints:
            sql += " CASCADE CONSTRAINTS"
        if op.purge:
            sql += " PURGE"
        return [Statement(sql)]

    if t == "ALTER_TABLE_OPTIONS":
        st = []
        if op.logging is not None:
            st.append(Statement(f"ALTER TABLE {tbl} {'LOGGING' if op.logging else 'NOLOGGING'}"))
        if op.compression:
            st.append(Statement(f"ALTER TABLE {tbl} {_COMPRESSION[op.compression]}"))
        if op.read_only is not None:
            st.append(Statement(f"ALTER TABLE {tbl} {'READ ONLY' if op.read_only else 'READ WRITE'}"))
        return st

    if t == "RENAME":
        if op.target == "TABLE":
            return [Statement(f"ALTER TABLE {s}.{op.old_name} RENAME TO {op.new_name}")]
        if op.target == "COLUMN":
            return [Statement(f"ALTER TABLE {tbl} RENAME COLUMN {op.old_name} TO {op.new_name}")]
        if op.target == "CONSTRAINT":
            return [Statement(f"ALTER TABLE {tbl} RENAME CONSTRAINT {op.old_name} TO {op.new_name}")]
        return [Statement(f"ALTER INDEX {s}.{op.old_name} RENAME TO {op.new_name}")]

    if t == "SET_COMMENT":
        if op.column:
            return [Statement(f"COMMENT ON COLUMN {tbl}.{op.column} IS {q(op.comment)}")]
        return [Statement(f"COMMENT ON TABLE {tbl} IS {q(op.comment)}")]

    if t == "ADD_CONSTRAINT":
        return [Statement(f"ALTER TABLE {tbl} ADD {render_constraint(op.constraint, s)}")]

    if t == "DROP_CONSTRAINT":
        sql = f"ALTER TABLE {tbl} DROP CONSTRAINT {op.constraint_name}"
        if op.cascade:
            sql += " CASCADE"
        if op.index:
            sql += f" {op.index} INDEX"
        return [Statement(sql)]

    if t == "CONSTRAINT_STATE":
        state = "DISABLE" if op.action == "DISABLE" else ("ENABLE" if op.validate_existing else "ENABLE NOVALIDATE")
        return [Statement(f"ALTER TABLE {tbl} MODIFY CONSTRAINT {op.constraint_name} {state}")]

    if t == "CREATE_INDEX":
        kind = {"UNIQUE": "UNIQUE ", "BITMAP": "BITMAP "}.get(op.index_type, "")
        cols = ", ".join(c.expression + (" DESC" if c.descending else "") for c in op.columns)
        sql = f"CREATE {kind}INDEX {s}.{op.index_name} ON {tbl} ({cols})"
        if op.index_type == "DOMAIN":
            sql += f" INDEXTYPE IS {op.indextype}"
            if op.parameters:
                sql += f" PARAMETERS ({q(op.parameters)})"
            return [Statement(sql)]
        if op.local:
            sql += " LOCAL"
        if op.reverse:
            sql += " REVERSE"
        if op.tablespace:
            sql += f" TABLESPACE {op.tablespace}"
        if op.compression == "PREFIX":
            sql += f" COMPRESS{f' {op.prefix_length}' if op.prefix_length else ''}"
        elif op.compression:
            sql += f" COMPRESS ADVANCED {op.compression.split('_')[1]}"
        if op.invisible:
            sql += " INVISIBLE"
        if op.online:
            sql += " ONLINE"
        return [Statement(sql)]

    if t == "DROP_INDEX":
        return [Statement(f"DROP INDEX {s}.{op.index_name}{' ONLINE' if op.online else ''}")]

    if t == "ALTER_INDEX":
        if op.action == "REBUILD":
            sql = f"ALTER INDEX {s}.{op.index_name} REBUILD"
            if op.tablespace:
                sql += f" TABLESPACE {op.tablespace}"
            if op.online:
                sql += " ONLINE"
            return [Statement(sql)]
        return [Statement(f"ALTER INDEX {s}.{op.index_name} {op.action}")]

    if t == "ADD_PARTITION":
        return [Statement(f"ALTER TABLE {tbl} ADD {_partition(op.partition, op.method)}")]

    if t == "DROP_PARTITION":
        return [Statement(f"ALTER TABLE {tbl} DROP PARTITION {op.partition_name}{' UPDATE INDEXES' if op.update_indexes else ''}")]

    if t in ("CREATE_SEQUENCE", "ALTER_SEQUENCE"):
        create = t == "CREATE_SEQUENCE"
        parts = []
        if create and op.start_with is not None:
            parts.append(f"START WITH {op.start_with}")
        if not create and op.restart_with is not None:
            parts.append(f"RESTART START WITH {op.restart_with}")
        if op.increment_by is not None:
            parts.append(f"INCREMENT BY {op.increment_by}")
        if op.min_value is not None:
            parts.append(f"MINVALUE {op.min_value}")
        if op.max_value is not None:
            parts.append(f"MAXVALUE {op.max_value}")
        if op.cache is not None:
            parts.append(f"CACHE {op.cache}" if op.cache > 1 else "NOCACHE")
        if op.cycle is not None and (op.cycle or not create):
            parts.append("CYCLE" if op.cycle else "NOCYCLE")
        if op.order is not None and (op.order or not create):
            parts.append("ORDER" if op.order else "NOORDER")
        if not create and not parts:
            raise ValueError("Indica al menos un cambio para la secuencia")
        verb = "CREATE" if create else "ALTER"
        return [Statement(f"{verb} SEQUENCE {s}.{op.name}" + (" " + " ".join(parts) if parts else ""))]

    if t == "CREATE_MV_LOG":
        withs = [w for w, on in (("PRIMARY KEY", op.with_primary_key), ("ROWID", op.with_rowid),
                                  ("SEQUENCE", op.with_sequence)) if on]
        sql = f"CREATE MATERIALIZED VIEW LOG ON {tbl} WITH {', '.join(withs)}" if withs else f"CREATE MATERIALIZED VIEW LOG ON {tbl} WITH"
        if op.columns:
            sql += f" ({', '.join(op.columns)})"
        if op.including_new_values:
            sql += " INCLUDING NEW VALUES"
        return [Statement(sql)]

    if t == "DROP_MV_LOG":
        return [Statement(f"DROP MATERIALIZED VIEW LOG ON {tbl}")]

    if t == "GRANT":
        cols = f" ({', '.join(op.columns)})" if op.columns else ""
        privs = ", ".join(p + cols for p in op.privileges)
        sql = f"GRANT {privs} ON {s}.{op.object} TO {op.grantee}"
        if op.with_grant_option:
            sql += " WITH GRANT OPTION"
        return [Statement(sql)]

    if t == "REVOKE":
        return [Statement(f"REVOKE {', '.join(op.privileges)} ON {s}.{op.object} FROM {op.grantee}")]

    if t == "CREATE_SYNONYM":
        replace = "OR REPLACE " if op.replace else ""
        if s == "PUBLIC":
            return [Statement(f"CREATE {replace}PUBLIC SYNONYM {op.synonym} FOR {op.target_schema}.{op.target_object}")]
        return [Statement(f"CREATE {replace}SYNONYM {s}.{op.synonym} FOR {op.target_schema}.{op.target_object}")]

    if t == "DROP_SYNONYM":
        if s == "PUBLIC":
            return [Statement(f"DROP PUBLIC SYNONYM {op.synonym}")]
        return [Statement(f"DROP SYNONYM {s}.{op.synonym}")]

    if t == "CREATE_USER":
        sql = f'CREATE USER {s} IDENTIFIED BY "{PASSWORD_PLACEHOLDER}"'
        if op.default_tablespace:
            sql += f" DEFAULT TABLESPACE {op.default_tablespace}"
        if op.quota:
            sql += f" QUOTA {op.quota} ON {op.default_tablespace or 'USERS'}"
        st = [Statement(sql, secret_placeholder=PASSWORD_PLACEHOLDER)]
        if op.system_privileges:
            st.append(Statement(f"GRANT {', '.join(op.system_privileges)} TO {s}"))
        if op.roles:
            st.append(Statement(f"GRANT {', '.join(op.roles)} TO {s}"))
        return st

    if t == "CREATE_OR_REPLACE_CODE":
        src = op.source.strip()
        if op.kind in ("VIEW", "MATERIALIZED VIEW"):
            return [Statement(src.rstrip(";").rstrip())]
        if not src.endswith(";"):
            src += ";"
        return [Statement(src, plsql=True)]

    if t == "DROP_CODE":
        return [Statement(f"DROP {op.kind} {s}.{op.name}")]

    if t == "CUSTOM_SQL":
        st = _split_custom_sql(op.sql)
        if not st:
            raise ValueError("El SQL personalizado está vacío")
        return st

    raise ValueError(f"Operación no soportada en Oracle: {t}")
