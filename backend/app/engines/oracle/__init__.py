"""Adaptador Oracle (python-oracledb en modo thin, sin Instant Client).

La exploración consulta exclusivamente vistas del diccionario (DBA_*) y DBMS_METADATA con la
cuenta de catálogo, que no tiene SELECT ANY TABLE: aunque hubiera un error, no podría leer datos.
"""
import re
import secrets
import string
from contextlib import contextmanager

import oracledb

from app.engines.base import ConnInfo, EngineAdapter, Finding, Statement, StatementResult
from app.engines.oracle import ddl

OBJECT_TYPES = ("TABLE", "VIEW", "MATERIALIZED VIEW", "SEQUENCE", "PROCEDURE", "FUNCTION", "PACKAGE",
                "SYNONYM", "TRIGGER", "TYPE")
_DDL_TYPE = {"MATERIALIZED VIEW": "MATERIALIZED_VIEW", "PACKAGE BODY": "PACKAGE_BODY"}
_ALWAYS_EXCLUDED = {"PDBADMIN"}
_INTERNAL_FILTER = """
       AND o.object_name NOT LIKE 'ISEQ$$%' AND o.object_name NOT LIKE 'MLOG$%' AND o.object_name NOT LIKE 'RUPD$%'
       AND NOT (o.object_type = 'TABLE' AND EXISTS (SELECT 1 FROM dba_mviews mv
                                                     WHERE mv.owner = o.owner AND mv.container_name = o.object_name))"""



@contextmanager
def _connect(info: ConnInfo, action: str, call_timeout_ms: int = 60_000):
    conn = oracledb.connect(
        user=info.user, password=info.password,
        dsn=f"{info.host}:{info.port}/{info.service_name}", tcp_connect_timeout=10,
    )
    try:
        conn.call_timeout = call_timeout_ms
        conn.module = "DBA Manager"
        conn.action = action[:32]
        if info.app_user:
            conn.client_identifier = info.app_user[:64]
        yield conn
    finally:
        conn.close()


def _rows(cur) -> list[dict]:
    cols = [d[0].lower() for d in cur.description]
    return [dict(zip(cols, r)) for r in cur.fetchall()]


def _query(conn, sql: str, **binds) -> list[dict]:
    with conn.cursor() as cur:
        cur.execute(sql, binds)
        return _rows(cur)


def _clean_default(value):
    return value.strip() if isinstance(value, str) else value


def _random_password() -> str:
    alphabet = string.ascii_letters + string.digits
    body = "".join(secrets.choice(alphabet) for _ in range(20))
    return f"Db{body}9"


class OracleAdapter(EngineAdapter):
    key = "oracle"
    label = "Oracle Database"
    data_types = ddl.DATA_TYPES

    def _excluded(self, info: ConnInfo) -> list[str]:
        return sorted({s.upper() for s in info.excluded_schemas} | _ALWAYS_EXCLUDED)

    # ------------------------------------------------------------------ conexión
    def test_connection(self, info: ConnInfo) -> dict:
        with _connect(info, "test") as conn:
            # v$version.banner_full solo existe desde 18c: se usa banner (12c+)
            row = _query(conn, """SELECT v.banner, SYS_CONTEXT('USERENV','CON_NAME') AS container
                                    FROM v$version v WHERE v.banner LIKE 'Oracle%' AND ROWNUM = 1""")
            privs = _query(conn, "SELECT privilege FROM session_privs ORDER BY 1")
            version = ddl.parse_version(conn.version)
            return {
                "version": conn.version,
                "supported": version is None or version >= ddl.MIN_VERSION,
                "banner": row[0]["banner"] if row else None,
                "container": row[0]["container"] if row else None,
                "session_privileges": [p["privilege"] for p in privs],
            }

    def server_version(self, info: ConnInfo) -> str:
        with _connect(info, "version") as conn:
            return conn.version

    # ------------------------------------------------------------------ exploración
    def list_schemas(self, info: ConnInfo) -> list[dict]:
        excluded = self._excluded(info)
        binds = {f"x{i}": s for i, s in enumerate(excluded)}
        not_in = ", ".join(f":x{i}" for i in range(len(excluded)))
        sql = f"""
            SELECT u.username AS name, u.account_status, u.created, u.default_tablespace,
                   (SELECT COUNT(*) FROM dba_objects o WHERE o.owner = u.username AND o.object_type = 'TABLE'
                      AND o.generated = 'N' AND o.object_name NOT LIKE 'BIN$%' {_INTERNAL_FILTER}) AS tables,
                   (SELECT COUNT(*) FROM dba_objects o WHERE o.owner = u.username
                      AND o.object_type IN ('TABLE','VIEW','MATERIALIZED VIEW','SEQUENCE','PROCEDURE','FUNCTION',
                                            'PACKAGE','SYNONYM','TRIGGER','TYPE')
                      AND o.generated = 'N' AND o.object_name NOT LIKE 'BIN$%' {_INTERNAL_FILTER}) AS objects,
                   (SELECT COUNT(*) FROM dba_tab_privs p WHERE p.grantee = u.username) AS received_grants
              FROM dba_users u
             WHERE u.oracle_maintained = 'N' AND u.username NOT IN ({not_in})
             ORDER BY u.username"""
        with _connect(info, "list_schemas") as conn:
            rows = _query(conn, sql, **binds)
        for r in rows:
            # Heurística: sin tablas propias pero con permisos recibidos => usuario lector
            r["kind"] = "READER" if r["tables"] == 0 and r["received_grants"] > 0 else (
                "OWNER" if r["tables"] > 0 else "EMPTY")
        return rows

    def list_objects(self, info: ConnInfo, schema: str) -> list[dict]:
        sql = f"""
            SELECT o.object_name AS name, o.object_type AS type, o.status, o.created, o.last_ddl_time,
                   tc.comments AS comments, s.table_owner AS target_schema, s.table_name AS target_object
              FROM dba_objects o
              LEFT JOIN dba_tab_comments tc ON tc.owner = o.owner AND tc.table_name = o.object_name
                                           AND o.object_type IN ('TABLE','VIEW')
              LEFT JOIN dba_synonyms s ON s.owner = o.owner AND s.synonym_name = o.object_name
                                      AND o.object_type = 'SYNONYM'
             WHERE o.owner = :owner AND o.generated = 'N' AND o.object_name NOT LIKE 'BIN$%' {_INTERNAL_FILTER}
               AND o.object_type IN ('TABLE','VIEW','MATERIALIZED VIEW','SEQUENCE','PROCEDURE','FUNCTION',
                                     'PACKAGE','SYNONYM','TRIGGER','TYPE')
             ORDER BY o.object_type, o.object_name"""
        with _connect(info, "list_objects") as conn:
            return _query(conn, sql, owner=schema.upper())

    def describe_table(self, info: ConnInfo, schema: str, name: str) -> dict:
        owner, table = schema.upper(), name.upper()
        b = {"owner": owner, "name": table}
        with _connect(info, "describe_table") as conn:
            obj = _query(conn, """
                SELECT o.object_type AS type, o.status, o.created, o.last_ddl_time, tc.comments,
                       t.tablespace_name, t.partitioned, t.temporary, t.duration, t.compression, t.compress_for,
                       t.logging, t.read_only, t.iot_type
                  FROM dba_objects o
                  LEFT JOIN dba_tab_comments tc ON tc.owner = o.owner AND tc.table_name = o.object_name
                  LEFT JOIN dba_tables t ON t.owner = o.owner AND t.table_name = o.object_name
                 WHERE o.owner = :owner AND o.object_name = :name
                   AND o.object_type IN ('TABLE','VIEW','MATERIALIZED VIEW')
                 ORDER BY DECODE(o.object_type,'MATERIALIZED VIEW',1,'TABLE',2,3)""", **b)
            if not obj:
                raise LookupError(f"No existe la tabla o vista {owner}.{table}")
            columns = _query(conn, """
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
                 ORDER BY c.column_id NULLS LAST, c.internal_column_id""", **b)
            constraints = _query(conn, """
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
                 ORDER BY DECODE(c.constraint_type,'P',1,'U',2,'R',3,4), c.constraint_name""", **b)
            referenced_by = _query(conn, """
                SELECT c.owner, c.table_name, c.constraint_name AS name,
                       (SELECT LISTAGG(cc.column_name, ', ') WITHIN GROUP (ORDER BY cc.position)
                          FROM dba_cons_columns cc
                         WHERE cc.owner = c.owner AND cc.constraint_name = c.constraint_name) AS columns
                  FROM dba_constraints c
                  JOIN dba_constraints p ON p.owner = c.r_owner AND p.constraint_name = c.r_constraint_name
                 WHERE c.constraint_type = 'R' AND p.owner = :owner AND p.table_name = :name
                 ORDER BY c.owner, c.table_name""", **b)
            indexes = _query(conn, """
                SELECT i.owner, i.index_name AS name, i.index_type AS type, i.uniqueness, i.status, i.visibility,
                       i.compression, i.prefix_length, i.partitioned, i.tablespace_name, i.ityp_owner, i.ityp_name,
                       i.parameters, i.locality,
                       (SELECT c.constraint_name FROM dba_constraints c
                         WHERE c.index_owner = i.owner AND c.index_name = i.index_name AND ROWNUM = 1) AS constraint_name
                  FROM (SELECT x.*, (SELECT locality FROM dba_part_indexes pi
                                      WHERE pi.owner = x.owner AND pi.index_name = x.index_name) AS locality
                          FROM dba_indexes x) i
                 WHERE i.table_owner = :owner AND i.table_name = :name AND i.index_type <> 'LOB'
                 ORDER BY i.index_name""", **b)
            ind_cols = _query(conn, """
                SELECT ic.index_owner, ic.index_name, ic.column_position, ic.column_name, ic.descend, e.column_expression
                  FROM dba_ind_columns ic
                  LEFT JOIN dba_ind_expressions e ON e.index_owner = ic.index_owner AND e.index_name = ic.index_name
                                                 AND e.column_position = ic.column_position
                 WHERE ic.table_owner = :owner AND ic.table_name = :name
                 ORDER BY ic.index_name, ic.column_position""", **b)
            grants = _query(conn, """
                SELECT grantee, privilege, grantable, grantor, NULL AS column_name
                  FROM dba_tab_privs WHERE owner = :owner AND table_name = :name
                UNION ALL
                SELECT grantee, privilege, grantable, grantor, column_name
                  FROM dba_col_privs WHERE owner = :owner AND table_name = :name
                 ORDER BY 1, 2""", **b)
            synonyms = _query(conn, """
                SELECT owner, synonym_name AS name FROM dba_synonyms
                 WHERE table_owner = :owner AND table_name = :name ORDER BY owner, synonym_name""", **b)
            part = _query(conn, """
                SELECT p.partitioning_type, p.subpartitioning_type, p.interval, p.partition_count,
                       p.def_tablespace_name, p.def_compression, p.def_compress_for, p.def_logging,
                       (SELECT LISTAGG(k.column_name, ', ') WITHIN GROUP (ORDER BY k.column_position)
                          FROM dba_part_key_columns k
                         WHERE k.owner = p.owner AND k.name = p.table_name AND k.object_type = 'TABLE') AS columns
                  FROM dba_part_tables p WHERE p.owner = :owner AND p.table_name = :name""", **b)
            partitions = _query(conn, """
                SELECT partition_name AS name, partition_position AS position, high_value, tablespace_name, interval
                  FROM dba_tab_partitions WHERE table_owner = :owner AND table_name = :name
                 ORDER BY partition_position FETCH FIRST 300 ROWS ONLY""", **b) if part else []
            triggers = _query(conn, """
                SELECT owner, trigger_name AS name, trigger_type, triggering_event, status
                  FROM dba_triggers WHERE table_owner = :owner AND table_name = :name ORDER BY trigger_name""", **b)
            mv_log = _query(conn, """
                SELECT log_table, primary_key, rowids, sequence, include_new_values
                  FROM dba_mview_logs WHERE log_owner = :owner AND master = :name""", **b)

        for c in columns:
            c["default_value"] = _clean_default(c["default_value"])
            c["nullable"] = c["nullable"] == "Y"
            c["identity"] = c.pop("identity_column") == "YES"
            c["default_on_null"] = c["default_on_null"] == "YES"
            c["invisible"] = c.pop("hidden_column") == "YES"
            c["virtual_expression"] = c["default_value"] if c.pop("virtual_column") == "YES" else None
            if c["virtual_expression"] or c["identity"]:
                c["default_value"] = None  # en virtuales es la expresión; en identidad, la secuencia ISEQ$$ interna
            c["char_semantics"] = c["char_used"] == "C"
            c["length"] = c["char_length"] if c["data_type"] in ("VARCHAR2", "NVARCHAR2", "CHAR", "NCHAR") else (
                c["data_length"] if c["data_type"] == "RAW" else None)
            if c["data_type"].startswith("TIMESTAMP"):
                m = re.match(r"TIMESTAMP\((\d)\)(.*)", c["data_type"])
                if m:  # el diccionario guarda TIMESTAMP(6) WITH TIME ZONE
                    c["data_type"], c["precision"] = "TIMESTAMP" + m.group(2), int(m.group(1))
            mode = c.pop("generation_type")
            opts = c.pop("identity_options") or ""
            if c["identity"]:
                start = re.search(r"START WITH: (-?\d+)", opts)
                inc = re.search(r"INCREMENT BY: (-?\d+)", opts)
                c["identity_mode"] = "ALWAYS" if mode == "ALWAYS" else ("BY_DEFAULT_ON_NULL" if c["default_on_null"] else "BY_DEFAULT")
                c["identity_start"] = int(start.group(1)) if start else None
                c["identity_increment"] = int(inc.group(1)) if inc else None
                c["default_on_null"] = False
            c["type_display"] = _type_display(c)
        # Los NOT NULL de columna son CHECK generados por el sistema: no se listan como restricciones
        constraints = [con for con in constraints if not (
            con["type"] == "C" and con["generated"] == "GENERATED NAME"
            and re.fullmatch(r'\s*"[^"]+"\s+IS\s+NOT\s+NULL\s*', con["condition"] or "", re.I))]
        for con in constraints:
            con["deferrable"] = con["deferrable"] == "DEFERRABLE"
            con["initially_deferred"] = con.pop("deferred") == "DEFERRED"
            con["validated"] = con["validated"] == "VALIDATED"
            con["enabled"] = con["status"] == "ENABLED"
        cols_by_index: dict[str, list[dict]] = {}
        for ic in ind_cols:
            expr = (ic["column_expression"] or "").strip() or ic["column_name"]
            cols_by_index.setdefault(ic["index_name"], []).append(
                {"expression": expr, "descending": ic["descend"] == "DESC"})
        for i in indexes:
            i["column_list"] = cols_by_index.get(i["name"], [])
            # DESC se implementa como índice por función: la expresión es la columna entre comillas
            for col in i["column_list"]:
                if re.fullmatch(r'"[A-Z][A-Z0-9_$#]*"', col["expression"]):
                    col["expression"] = col["expression"].strip('"')
            i["columns"] = ", ".join(c["expression"] + (" DESC" if c["descending"] else "") for c in i["column_list"])
            i["kind"] = ("DOMAIN" if i["type"] == "DOMAIN" else "BITMAP" if "BITMAP" in i["type"]
                         else "UNIQUE" if i["uniqueness"] == "UNIQUE" else "NORMAL")
            i["function_based"] = "FUNCTION-BASED" in i["type"] and not all(
                re.fullmatch(r"[A-Z][A-Z0-9_$#]*", c["expression"]) for c in i["column_list"])
            i["reverse"] = i["type"].endswith("/REV")
            i["indextype"] = f"{i['ityp_owner']}.{i['ityp_name']}" if i.pop("ityp_name") else None
            i.pop("ityp_owner")
        grouped: dict[str, dict] = {}
        for g in grants:
            e = grouped.setdefault(g["grantee"], {"grantee": g["grantee"], "privileges": [], "grantable": [], "column_privileges": []})
            if g["column_name"]:
                e["column_privileges"].append(f"{g['privilege']}({g['column_name']})")
                continue
            e["privileges"].append(g["privilege"])
            if g["grantable"] == "YES":
                e["grantable"].append(g["privilege"])
        props = obj[0]
        if part:  # en tablas particionadas estas propiedades viven en los valores por defecto de las particiones
            pp = part[0]
            props["tablespace_name"] = props["tablespace_name"] or pp.pop("def_tablespace_name")
            props["compression"] = props["compression"] or pp.pop("def_compression")
            props["compress_for"] = props["compress_for"] or pp.pop("def_compress_for")
            props["logging"] = props["logging"] or pp.pop("def_logging")
        return {
            "schema": owner, "name": table, **props,
            "columns": columns, "constraints": constraints, "referenced_by": referenced_by,
            "indexes": indexes, "grants": list(grouped.values()), "synonyms": synonyms,
            "partitioning": part[0] if part else None, "partitions": partitions,
            "triggers": triggers, "mv_log": mv_log[0] if mv_log else None,
        }

    def describe_user(self, info: ConnInfo, schema: str) -> dict:
        owner = schema.upper()
        with _connect(info, "describe_user") as conn:
            account = _query(conn, """
                SELECT username, account_status, created, default_tablespace, temporary_tablespace, profile,
                       lock_date, expiry_date, authentication_type
                  FROM dba_users WHERE username = :u""", u=owner)
            if not account:
                raise LookupError(f"No existe el usuario {owner}")
            return {
                **account[0],
                "system_privileges": _query(conn, "SELECT privilege, admin_option FROM dba_sys_privs WHERE grantee = :u ORDER BY 1", u=owner),
                "roles": _query(conn, "SELECT granted_role AS role, admin_option, default_role FROM dba_role_privs WHERE grantee = :u ORDER BY 1", u=owner),
                "quotas": _query(conn, "SELECT tablespace_name, bytes, max_bytes FROM dba_ts_quotas WHERE username = :u", u=owner),
                "object_privileges": _query(conn, """
                    SELECT p.owner, p.table_name AS object_name, o.object_type,
                           LISTAGG(p.privilege, ', ') WITHIN GROUP (ORDER BY p.privilege) AS privileges,
                           MAX(p.grantable) AS grantable
                      FROM dba_tab_privs p
                      LEFT JOIN dba_objects o ON o.owner = p.owner AND o.object_name = p.table_name
                                             AND o.object_type NOT IN ('PACKAGE BODY','TYPE BODY','INDEX')
                     WHERE p.grantee = :u
                     GROUP BY p.owner, p.table_name, o.object_type
                     ORDER BY p.owner, p.table_name""", u=owner),
                "synonyms": _query(conn, """
                    SELECT synonym_name AS name, table_owner AS target_schema, table_name AS target_object
                      FROM dba_synonyms WHERE owner = :u ORDER BY 1""", u=owner),
            }

    def get_ddl(self, info: ConnInfo, schema: str, object_type: str, name: str) -> str:
        otype = object_type.upper()
        if otype not in OBJECT_TYPES and otype != "PACKAGE BODY":
            raise ValueError(f"Tipo de objeto no soportado: {object_type}")
        md_type = _DDL_TYPE.get(otype, otype)
        with _connect(info, "get_ddl") as conn, conn.cursor() as cur:
            cur.execute("""
                BEGIN
                  DBMS_METADATA.SET_TRANSFORM_PARAM(DBMS_METADATA.SESSION_TRANSFORM, 'STORAGE', FALSE);
                  DBMS_METADATA.SET_TRANSFORM_PARAM(DBMS_METADATA.SESSION_TRANSFORM, 'SEGMENT_ATTRIBUTES', FALSE);
                  DBMS_METADATA.SET_TRANSFORM_PARAM(DBMS_METADATA.SESSION_TRANSFORM, 'SQLTERMINATOR', TRUE);
                  DBMS_METADATA.SET_TRANSFORM_PARAM(DBMS_METADATA.SESSION_TRANSFORM, 'PRETTY', TRUE);
                END;""")
            cur.execute("SELECT DBMS_METADATA.GET_DDL(:t, :n, :o) FROM dual", t=md_type, n=name.upper(), o=schema.upper())
            parts = [cur.fetchone()[0].read().strip()]
            if otype in ("TABLE", "VIEW", "MATERIALIZED VIEW"):
                for dep in ("COMMENT", "INDEX", "OBJECT_GRANT"):
                    if otype != "TABLE" and dep == "INDEX":
                        continue
                    try:
                        cur.execute("SELECT DBMS_METADATA.GET_DEPENDENT_DDL(:t, :n, :o) FROM dual",
                                    t=dep, n=name.upper(), o=schema.upper())
                        parts.append(cur.fetchone()[0].read().strip())
                    except oracledb.DatabaseError:
                        pass  # ORA-31608: no hay objetos dependientes de ese tipo
            return "\n\n".join(parts)

    def search(self, info: ConnInfo, text: str) -> list[dict]:
        pattern = f"%{text.upper()}%"
        excluded = self._excluded(info)
        binds = {f"x{i}": s for i, s in enumerate(excluded)}
        not_in = ", ".join(f":x{i}" for i in range(len(excluded)))
        sql = f"""
            SELECT * FROM (
              SELECT 'OBJECT' AS match_on, o.owner AS schema, o.object_type, o.object_name,
                     NULL AS column_name, tc.comments
                FROM dba_objects o
                JOIN dba_users u ON u.username = o.owner AND u.oracle_maintained = 'N'
                LEFT JOIN dba_tab_comments tc ON tc.owner = o.owner AND tc.table_name = o.object_name
               WHERE o.object_type IN ('TABLE','VIEW','MATERIALIZED VIEW','PROCEDURE','FUNCTION','PACKAGE','SEQUENCE')
                 AND o.owner NOT IN ({not_in}) AND o.generated = 'N' {_INTERNAL_FILTER}
                 AND (o.object_name LIKE :p OR UPPER(tc.comments) LIKE :p)
              UNION ALL
              SELECT 'COLUMN', c.owner, 'TABLE', c.table_name, c.column_name, cc.comments
                FROM dba_tab_columns c
                JOIN dba_users u ON u.username = c.owner AND u.oracle_maintained = 'N'
                LEFT JOIN dba_col_comments cc ON cc.owner = c.owner AND cc.table_name = c.table_name
                                             AND cc.column_name = c.column_name
               WHERE c.owner NOT IN ({not_in}) AND c.table_name NOT LIKE 'BIN$%'
                 AND c.table_name NOT LIKE 'MLOG$%' AND c.table_name NOT LIKE 'RUPD$%'
                 AND (c.column_name LIKE :p OR UPPER(cc.comments) LIKE :p)
            ) ORDER BY schema, object_name, column_name FETCH FIRST 300 ROWS ONLY"""
        with _connect(info, "search") as conn:
            return _query(conn, sql, p=pattern, **binds)

    # ------------------------------------------------------------------ generación / validación
    def render(self, op, version: str | None = None) -> list[Statement]:
        return ddl.render(op, ddl.parse_version(version))

    def preflight(self, info: ConnInfo, ops: list) -> list[Finding]:
        """Revisa contra el diccionario si las operaciones tienen sentido (no ejecuta nada)."""
        from app.engines.oracle.preflight import Preflight

        with _connect(info, "preflight") as conn:
            return Preflight(lambda sql, **b: _query(conn, sql, **b)).run(ops)

    def import_script(self, info: ConnInfo, sql: str, default_schema: str) -> list[dict]:
        from app.engines.oracle.sql_import import import_script

        tables: dict[tuple[str, str], dict | None] = {}

        def lookup_column(schema: str, table: str, column: str) -> dict | None:
            if (schema, table) not in tables:
                try:
                    tables[(schema, table)] = self.describe_table(info, schema, table)
                except LookupError:
                    tables[(schema, table)] = None
            t = tables[(schema, table)]
            c = next((c for c in (t or {}).get("columns", []) if c["name"] == column), None)
            if c is None:
                return None
            return catalog_column_to_op(c)

        return [{"op": it.op, "notes": it.notes, "source": it.source, "absorbed": it.absorbed,
                 "companion": it.companion} for it in import_script(sql, default_schema, lookup_column)]

    # ------------------------------------------------------------------ ejecución
    def execute(self, info: ConnInfo, statements: list[Statement], tag: str) -> list[StatementResult]:
        """Ejecuta en orden y se detiene en el primer error. El DDL en Oracle hace commit implícito."""
        results: list[StatementResult] = []
        with _connect(info, f"exec {tag}", call_timeout_ms=15 * 60_000) as conn, conn.cursor() as cur:
            for st in statements:
                sql, secret = st.sql, None
                if st.secret_placeholder:
                    secret = _random_password()
                    sql = sql.replace(st.secret_placeholder, secret)
                try:
                    cur.execute(sql)
                    warning = getattr(cur, "warning", None)
                    if warning is not None:
                        errors = _plsql_errors(conn, st.sql)
                        results.append(StatementResult(st.sql, False, f"Compilado con errores: {warning}\n{errors}"))
                        break
                    conn.commit()
                    results.append(StatementResult(st.sql, True, "OK", secret))
                except oracledb.DatabaseError as exc:
                    conn.rollback()
                    results.append(StatementResult(st.sql, False, str(exc)))
                    break
        return results


def catalog_column_to_op(c: dict) -> dict:
    """Columna de describe_table -> definición de columna de operación (app.operations.Column)."""
    return {
        "name": c["name"], "data_type": c["data_type"], "length": c["length"],
        "char_semantics": c["char_semantics"], "precision": c["precision"], "scale": c["scale"],
        "nullable": c["nullable"], "default": c["default_value"], "default_on_null": c["default_on_null"],
        "comment": c["comments"], "identity": c["identity"],
        "identity_mode": c.get("identity_mode", "BY_DEFAULT_ON_NULL"), "identity_start": c.get("identity_start"),
        "identity_increment": c.get("identity_increment"), "virtual_expression": c["virtual_expression"],
        "invisible": c["invisible"],
    }


def _plsql_errors(conn, sql: str) -> str:
    m = re.search(r"(PACKAGE\s+BODY|PACKAGE|PROCEDURE|FUNCTION|TRIGGER|TYPE|VIEW)\s+\"?(\w+)\"?\.\"?(\w+)\"?",
                  sql, re.IGNORECASE)
    if not m:
        return ""
    rows = _query(conn, """SELECT line, position, text FROM dba_errors
                             WHERE owner = :o AND name = :n AND type = :t ORDER BY sequence""",
                  o=m.group(2).upper(), n=m.group(3).upper(), t=re.sub(r"\s+", " ", m.group(1).upper()))
    return "\n".join(f"  línea {r['line']}:{r['position']} {r['text']}" for r in rows)


def _type_display(c: dict) -> str:
    dt = c["data_type"]
    if dt in ("VARCHAR2", "NVARCHAR2", "CHAR", "NCHAR"):
        unit = " CHAR" if c.get("char_used") == "C" and dt in ("VARCHAR2", "CHAR") else ""
        return f"{dt}({c['char_length']}{unit})"
    if dt.startswith("TIMESTAMP") and c.get("precision") is not None:
        return dt.replace("TIMESTAMP", f"TIMESTAMP({c['precision']})", 1)
    if dt == "RAW":
        return f"RAW({c['data_length']})"
    if dt == "NUMBER" and c["precision"] is not None:
        return f"NUMBER({c['precision']},{c['scale']})" if c["scale"] else f"NUMBER({c['precision']})"
    if dt == "NUMBER" and c["scale"] == 0:
        return "INTEGER"
    return dt
