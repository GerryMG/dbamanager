"""Convierte un script SQL de Oracle en operaciones visuales de DBA Manager.

Lo que se reconoce se transforma en operaciones estructuradas (CREATE_TABLE, CREATE_INDEX,
ADD_CONSTRAINT, GRANT, ...); lo que no puede representarse sin cambiar su significado se conserva
como CUSTOM_SQL para que nada del script se pierda.
"""
import re
from dataclasses import dataclass, field

from app.engines.oracle.ddl import DATA_TYPES
from app.operations import OBJECT_PRIVILEGES, SYSTEM_PRIVILEGES

NAME = r'(?:"[^"]+"|[A-Za-z][\w$#]*)'
QNAME = rf"({NAME})(?:\s*\.\s*({NAME}))?"
PLSQL_START = re.compile(
    r"^\s*(DECLARE|BEGIN|CREATE\s+(OR\s+REPLACE\s+)?(EDITIONABLE\s+|NONEDITIONABLE\s+)?"
    r"(PROCEDURE|FUNCTION|PACKAGE|TRIGGER|TYPE)\b)", re.I)
_TYPES = {t["name"] for t in DATA_TYPES}
_TYPE_ALIASES = {"VARCHAR": "VARCHAR2", "INT": "INTEGER", "SMALLINT": "INTEGER", "DECIMAL": "NUMBER", "NUMERIC": "NUMBER"}
# Atributos físicos que se ignoran (la base usa sus valores por defecto)
_PHYSICAL = re.compile(
    r"\b(PCTFREE\s+\d+|PCTUSED\s+\d+|INITRANS\s+\d+|MAXTRANS\s+\d+|STORAGE\s*\([^)]*\)|NOCACHE|CACHE"
    r"|NOPARALLEL|PARALLEL(\s+\d+)?|NOMONITORING|MONITORING|SEGMENT\s+CREATION\s+(IMMEDIATE|DEFERRED)"
    r"|ENABLE\s+ROW\s+MOVEMENT|DISABLE\s+ROW\s+MOVEMENT|NOSORT|SORT|COMPUTE\s+STATISTICS"
    r"|NOROWDEPENDENCIES|ROWDEPENDENCIES|NOFLASHBACK\s+ARCHIVE)\b", re.I)


@dataclass
class Imported:
    op: dict
    notes: list[str] = field(default_factory=list)
    source: str = ""  # sentencia original del script
    absorbed: list[str] = field(default_factory=list)  # sentencias fusionadas (COMMENT, GRANT de sistema)
    companion: bool = False  # operación derivada de otra sentencia (se descarta si ésta queda como SQL)


# ------------------------------------------------------------------ utilidades léxicas
def strip_comments(text: str) -> str:
    """Quita comentarios -- y /* */ respetando literales entre comillas."""
    out, i, n = [], 0, len(text)
    while i < n:
        c = text[i]
        if c in ("'", '"'):
            j = i + 1
            while j < n:
                if text[j] == c:
                    if c == "'" and j + 1 < n and text[j + 1] == "'":
                        j += 2
                        continue
                    break
                j += 1
            out.append(text[i:j + 1])
            i = j + 1
        elif text.startswith("--", i):
            j = text.find("\n", i)
            i = n if j < 0 else j
        elif text.startswith("/*", i):
            j = text.find("*/", i + 2)
            out.append(" ")
            i = n if j < 0 else j + 2
        else:
            out.append(c)
            i += 1
    return "".join(out).strip()


def split_script(sql: str) -> list[str]:
    """Divide en sentencias: ';' para SQL y una línea '/' para bloques PL/SQL."""
    stmts: list[str] = []
    buf: list[str] = []
    in_q = in_dq = in_block = False

    def emit():
        text = "".join(buf).strip()
        buf.clear()
        if strip_comments(text):
            stmts.append(text)

    for line in sql.splitlines(keepends=True):
        if not (in_q or in_dq or in_block) and line.strip() == "/":
            emit()
            continue
        i, n = 0, len(line)
        while i < n:
            c = line[i]
            if in_block:
                if line.startswith("*/", i):
                    in_block = False
                    buf.append("*/")
                    i += 2
                    continue
            elif in_q:
                in_q = c != "'"
            elif in_dq:
                in_dq = c != '"'
            elif line.startswith("--", i):
                buf.append(line[i:])
                break
            elif line.startswith("/*", i):
                in_block = True
                buf.append("/*")
                i += 2
                continue
            elif c == "'":
                in_q = True
            elif c == '"':
                in_dq = True
            elif c == ";" and not PLSQL_START.match(strip_comments("".join(buf))):
                emit()
                i += 1
                continue
            buf.append(c)
            i += 1
    emit()
    return stmts


def split_top(text: str, sep: str = ",") -> list[str]:
    """Divide por `sep` a nivel 0 de paréntesis y fuera de comillas."""
    parts, depth, cur, q = [], 0, [], None
    for c in text:
        if q:
            q = None if c == q else q
        elif c in ("'", '"'):
            q = c
        elif c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
        elif c == sep and depth == 0:
            parts.append("".join(cur).strip())
            cur = []
            continue
        cur.append(c)
    if "".join(cur).strip():
        parts.append("".join(cur).strip())
    return parts


def matching_paren(text: str, start: int) -> int:
    depth, q = 0, None
    for i in range(start, len(text)):
        c = text[i]
        if q:
            q = None if c == q else q
        elif c in ("'", '"'):
            q = c
        elif c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
            if depth == 0:
                return i
    raise ValueError("Paréntesis sin cerrar")


def paren_body(text: str) -> tuple[str, str]:
    """'(a, b) resto' -> ('a, b', 'resto')."""
    text = text.lstrip()
    if not text.startswith("("):
        raise ValueError(f"Se esperaba '(' en: {text[:40]}")
    end = matching_paren(text, 0)
    return text[1:end], text[end + 1:]


def unq(name: str | None) -> str | None:
    if name is None:
        return None
    if name.startswith('"'):
        inner = name[1:-1]
        if inner != inner.upper() or not re.match(r"^[A-Z][A-Z0-9_$#]*$", inner):
            # Convertirlo cambiaría el nombre real del objeto: se conserva el SQL tal cual.
            raise ValueError(f'el nombre "{inner}" distingue mayúsculas/minúsculas o tiene caracteres especiales')
        return inner
    return name.upper()


def qname(a: str, b: str | None, default_schema: str) -> tuple[str, str]:
    return (unq(a), unq(b)) if b else (default_schema, unq(a))


def names(text: str) -> list[str]:
    return [unq(c.strip()) for c in split_top(text)]


def unescape(lit: str) -> str:
    return lit.replace("''", "'")


def _search_top(pattern: str, text: str):
    """Como re.search pero solo a nivel superior (fuera de paréntesis y comillas)."""
    rx = re.compile(pattern, re.I | re.S)
    depth, q, i = 0, None, 0
    while i < len(text):
        c = text[i]
        if q:
            q = None if c == q else q
        elif c in ("'", '"'):
            q = c
        elif c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
        elif depth == 0 and (i == 0 or not (text[i - 1].isalnum() or text[i - 1] in "_$#")):
            m = rx.match(text, i)
            if m:
                return m
        i += 1
    return None


def take(pattern: str, text: str) -> tuple[re.Match | None, str]:
    """Busca `pattern` a nivel superior y lo quita del texto."""
    m = _search_top(pattern, text)
    if not m:
        return None, text
    return m, (text[:m.start()] + " " + text[m.end():]).strip()


# ------------------------------------------------------------------ tipos y columnas
_CLAUSE_KW = re.compile(
    r"(GENERATED\s+(?:ALWAYS|BY\s+DEFAULT(?:\s+ON\s+NULL)?)\s+AS\s+IDENTITY\b|(?:GENERATED\s+ALWAYS\s+)?AS\s*\("
    r"|ON\s+DELETE\s+(?:CASCADE|SET\s+NULL)\b|DEFAULT\s+ON\s+NULL\b|DEFAULT\b|NOT\s+NULL\b|NULL\b|CONSTRAINT\b|PRIMARY\s+KEY\b|UNIQUE\b|CHECK\b|REFERENCES\b"
    r"|ENABLE\b|DISABLE\b|VISIBLE\b|INVISIBLE\b|VIRTUAL\b|USING\s+INDEX\b"
    r"|NOT\s+DEFERRABLE\b|DEFERRABLE\b|INITIALLY\b|VALIDATE\b|NOVALIDATE\b|RELY\b|NORELY\b)", re.I)


def _norm_kw(raw: str) -> str:
    kw = re.sub(r"\s+", " ", raw.upper()).strip()
    if kw.startswith("GENERATED") and kw.endswith("IDENTITY"):
        return "IDENTITY"
    if kw.endswith("("):
        return "AS ("
    if kw.startswith("DEFAULT"):
        return "DEFAULT"
    if kw.startswith("ON DELETE"):
        return "ON DELETE"
    return kw


_STATE_KW = ("DEFERRABLE", "NOT DEFERRABLE", "INITIALLY", "ENABLE", "DISABLE", "VALIDATE", "NOVALIDATE",
             "USING INDEX", "RELY", "NORELY")


def split_clauses(rest: str) -> list[tuple[str, str]]:
    """Separa '... DEFAULT x NOT NULL CONSTRAINT c CHECK (...)' en (palabra clave, texto)."""
    marks, depth, q, i = [], 0, None, 0
    while i < len(rest):
        c = rest[i]
        if q:
            q = None if c == q else q
        elif c in ("'", '"'):
            q = c
        elif c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
        elif depth == 0 and (i == 0 or not (rest[i - 1].isalnum() or rest[i - 1] in "_$#")):
            m = _CLAUSE_KW.match(rest, i)
            if m:
                marks.append(i)
                i = m.end()
                if m.group(1).endswith("("):
                    depth += 1  # el paréntesis ya consumido por la palabra clave
                continue
        i += 1
    if (marks and rest[:marks[0]].strip()) or (not marks and rest.strip()):
        raise ValueError(f"No se reconoce: {rest[:marks[0] if marks else len(rest)].strip()[:60]}")
    out = []
    for k, pos in enumerate(marks):
        chunk = rest[pos:marks[k + 1] if k + 1 < len(marks) else len(rest)].strip()
        kw = _norm_kw(_CLAUSE_KW.match(chunk).group(1))
        if out and out[-1][0] == "DEFAULT" and kw == "NULL" and re.fullmatch(r"DEFAULT(\s+ON\s+NULL)?", out[-1][1], re.I):
            out[-1] = ("DEFAULT", out[-1][1] + " " + chunk)  # DEFAULT NULL
            continue
        out.append((kw, chunk))
    return out


def default_expr(chunk: str) -> str | None:
    expr = re.sub(r"^DEFAULT\s+(ON\s+NULL\s+)?", "", chunk, flags=re.I).strip()
    return None if not expr or expr.upper() == "NULL" else expr


def parse_type(text: str) -> tuple[dict, str]:
    """Devuelve (campos de tipo, resto del texto)."""
    m = re.match(r"\s*TIMESTAMP\s*(?:\(\s*(\d+)\s*\))?(\s+WITH\s+(LOCAL\s+)?TIME\s+ZONE)?", text, re.I)
    if m:
        name = "TIMESTAMP" + (" WITH LOCAL TIME ZONE" if m.group(3) else " WITH TIME ZONE" if m.group(2) else "")
        return {"data_type": name, "precision": int(m.group(1)) if m.group(1) else None}, text[m.end():]
    m = re.match(r"\s*([A-Za-z_][A-Za-z0-9_]*)\s*(\(([^)]*)\))?", text)
    if not m:
        raise ValueError("No se reconoce el tipo de dato")
    name = m.group(1).upper()
    name = _TYPE_ALIASES.get(name, name)
    if name not in _TYPES:
        raise ValueError(f"tipo de dato {name} no soportado visualmente")
    args = [a.strip().upper() for a in (m.group(3) or "").split(",") if a.strip()]
    out: dict = {"data_type": name}
    if args:
        if name in ("VARCHAR2", "NVARCHAR2", "CHAR", "NCHAR", "RAW"):
            size = args[0].split()
            out["length"] = int(size[0])
            if len(size) > 1 and size[1] == "CHAR" and name in ("VARCHAR2", "CHAR"):
                out["char_semantics"] = True
        elif name == "NUMBER":
            if args[0] != "*":
                out["precision"] = int(args[0])
            if len(args) > 1:
                out["scale"] = int(args[1])
        elif name == "FLOAT":
            out["precision"] = int(args[0])
    return out, text[m.end():]


def parse_constraint_state(chunks: list[tuple[str, str]], c: dict, notes: list[str]) -> None:
    for kw, chunk in chunks:
        up = re.sub(r"\s+", " ", chunk.upper())
        if kw == "DEFERRABLE":
            c["deferrable"] = True
        elif kw == "INITIALLY":
            c["initially_deferred"] = "DEFERRED" in up
        elif kw == "DISABLE":
            c["enabled"] = False
        elif kw == "ENABLE":
            c["enabled"] = True
        elif kw == "NOVALIDATE":
            c["validated"] = False
        elif kw == "USING INDEX":
            ts = re.search(rf"TABLESPACE\s+({NAME})", chunk, re.I)
            if ts:
                c["index_tablespace"] = unq(ts.group(1))
            rest = re.sub(rf"USING\s+INDEX|TABLESPACE\s+{NAME}", "", chunk, flags=re.I)
            if rest.strip():
                if _PHYSICAL.sub("", rest).strip():
                    raise ValueError(f"opciones USING INDEX no soportadas: {rest.strip()[:60]}")
                notes.append("Se ignoraron opciones físicas del índice de la restricción")
        elif kw in ("VALIDATE", "NOT DEFERRABLE", "RELY", "NORELY"):
            pass
        else:
            raise ValueError(f"cláusula {kw} no soportada en la restricción")


def parse_table_constraint(text: str, notes: list[str]) -> dict:
    """'[CONSTRAINT x] PRIMARY KEY (..)|UNIQUE (..)|FOREIGN KEY (..) REFERENCES t (..) ...|CHECK (..) [estado]'."""
    c: dict = {}
    m = re.match(rf"^\s*CONSTRAINT\s+({NAME})\s+(.*)$", text, re.I | re.S)
    if m:
        c["name"], text = unq(m.group(1)), m.group(2)
    text = text.strip()
    m = re.match(r"^(PRIMARY\s+KEY|UNIQUE|FOREIGN\s+KEY|CHECK)\s*", text, re.I)
    if not m:
        raise ValueError(f"restricción no reconocida: {text[:50]}")
    kind = re.sub(r"\s+", "_", m.group(1).upper())
    body, rest = paren_body(text[m.end():])
    c["kind"] = kind
    if kind == "CHECK":
        c["condition"] = body.strip()
    else:
        c["columns"] = names(body)
    if kind == "FOREIGN_KEY":
        r = re.match(rf"^\s*REFERENCES\s+{QNAME}\s*", rest, re.I)
        if not r:
            raise ValueError("FOREIGN KEY sin REFERENCES")
        c["ref_schema"], c["ref_table"] = (unq(r.group(1)), unq(r.group(2))) if r.group(2) else (None, unq(r.group(1)))
        rest = rest[r.end():]
        if rest.lstrip().startswith("("):
            rb, rest = paren_body(rest)
            c["ref_columns"] = names(rb)
        od = re.match(r"^\s*ON\s+DELETE\s+(CASCADE|SET\s+NULL)", rest, re.I)
        if od:
            c["on_delete"] = "CASCADE" if od.group(1).upper() == "CASCADE" else "SET_NULL"
            rest = rest[od.end():]
    parse_constraint_state(split_clauses(rest) if rest.strip() else [], c, notes)
    return c


def parse_column(text: str, notes: list[str]) -> tuple[dict, list[dict]]:
    """Devuelve (columna, restricciones inline como ConstraintDef)."""
    m = re.match(rf"\s*({NAME})\s+(.*)$", text, re.S)
    if not m:
        raise ValueError(f"No se reconoce la columna: {text[:60]}")
    col_name = unq(m.group(1))
    if re.match(r"^\s*(GENERATED\s+ALWAYS\s+)?AS\s*\(", m.group(2), re.I):
        col, rest = {"data_type": None}, m.group(2)  # columna virtual sin tipo explícito
    else:
        col, rest = parse_type(m.group(2))
    col.update({"name": col_name, "nullable": True})
    constraints: list[dict] = []
    cur: dict | None = None  # restricción inline con nombre en construcción
    clauses = split_clauses(rest) if rest.strip() else []
    k = 0
    while k < len(clauses):
        kw, chunk = clauses[k]
        k += 1
        if kw == "DEFAULT":
            col["default_on_null"] = bool(re.match(r"^DEFAULT\s+ON\s+NULL\b", chunk, re.I))
            col["default"] = default_expr(chunk)
        elif kw == "IDENTITY":
            up = re.sub(r"\s+", " ", chunk.upper())
            col["identity"] = True
            col["nullable"] = False
            col["identity_mode"] = ("ALWAYS" if up.startswith("GENERATED ALWAYS") else
                                    "BY_DEFAULT_ON_NULL" if "ON NULL" in up else "BY_DEFAULT")
            st = re.search(r"START\s+WITH\s+(-?\d+)", chunk, re.I)
            inc = re.search(r"INCREMENT\s+BY\s+(-?\d+)", chunk, re.I)
            col["identity_start"] = int(st.group(1)) if st else None
            col["identity_increment"] = int(inc.group(1)) if inc else None
            opts = re.sub(r"^GENERATED.*?IDENTITY|START\s+WITH\s+-?\d+|INCREMENT\s+BY\s+-?\d+|[()\s]", "",
                          chunk, flags=re.I | re.S)
            if opts:
                notes.append(f"{col_name}: opciones de identidad (CACHE, MAXVALUE…) ignoradas")
        elif kw == "AS (":
            inner, after = paren_body(chunk[chunk.index("("):])
            col["virtual_expression"] = inner.strip()
            if after.strip() and after.strip().upper() != "VIRTUAL":
                raise ValueError(f"{col_name}: cláusula no reconocida tras la expresión virtual")
        elif kw == "VIRTUAL":
            pass
        elif kw == "INVISIBLE":
            col["invisible"] = True
        elif kw == "VISIBLE":
            col["invisible"] = False
        elif kw == "NOT NULL":
            col["nullable"] = False
        elif kw == "NULL":
            col["nullable"] = True
        elif kw == "CONSTRAINT":
            cur = {"name": unq(chunk.split()[1])}
        elif kw in ("PRIMARY KEY", "UNIQUE", "CHECK", "REFERENCES"):
            c = cur or {}
            cur = None
            if kw == "PRIMARY KEY":
                c.update({"kind": "PRIMARY_KEY", "columns": [col_name]})
                col["nullable"] = False
            elif kw == "UNIQUE":
                c.update({"kind": "UNIQUE", "columns": [col_name]})
            elif kw == "CHECK":
                c.update({"kind": "CHECK", "condition": paren_body(chunk[5:])[0].strip()})
            else:
                if k < len(clauses) and clauses[k][0] == "ON DELETE":
                    chunk += " " + clauses[k][1]
                    k += 1
                fk = parse_table_constraint(f"FOREIGN KEY ({col_name}) {chunk}", notes)
                c.update({k2: v for k2, v in fk.items() if k2 != "name"})
            states = []  # estados que siguen a la restricción (DEFERRABLE, DISABLE, ...)
            while k < len(clauses) and clauses[k][0] in _STATE_KW:
                states.append(clauses[k])
                k += 1
            parse_constraint_state(states, c, notes)
            constraints.append(c)
        elif kw in ("ENABLE", "VALIDATE"):
            pass
        else:
            raise ValueError(f"{col_name}: cláusula {kw} no soportada visualmente")
    return col, constraints


# ------------------------------------------------------------------ opciones de tabla y particiones
def parse_partitioning(text: str) -> tuple[dict, str]:
    m = re.match(r"^\s*PARTITION\s+BY\s+(RANGE|LIST|HASH)\s*", text, re.I)
    if not m:
        raise ValueError("tipo de particionado no soportado visualmente")
    method = m.group(1).upper()
    cols, rest = paren_body(text[m.end():])
    pt: dict = {"method": method, "columns": names(cols), "partitions": []}
    im = re.match(r"^\s*INTERVAL\s*", rest, re.I)
    if im:
        iv, rest = paren_body(rest[im.end():])
        pt["interval"] = iv.strip()
    if re.match(r"^\s*SUBPARTITION\b", rest, re.I):
        raise ValueError("subparticiones no soportadas visualmente")
    hm = re.match(r"^\s*PARTITIONS\s+(\d+)", rest, re.I)
    if hm:
        pt["hash_count"] = int(hm.group(1))
        rest = rest[hm.end():]
        sm = re.match(r"^\s*STORE\s+IN\s*\([^)]*\)", rest, re.I)
        if sm:
            rest = rest[sm.end():]
        return pt, rest
    body, rest = paren_body(rest)
    for p in split_top(body):
        pm = re.match(rf"^\s*PARTITION\s+({NAME})\s*(.*)$", p, re.I | re.S)
        if not pm:
            raise ValueError(f"partición no reconocida: {p[:50]}")
        part: dict = {"name": unq(pm.group(1))}
        prest = pm.group(2)
        vm = re.match(r"^\s*VALUES\s+(LESS\s+THAN\s*)?", prest, re.I)
        if vm:
            vals, prest = paren_body(prest[vm.end():])
            part["values"] = vals.strip()
        tm, prest = take(rf"TABLESPACE\s+({NAME})", prest)
        if tm:
            part["tablespace"] = unq(tm.group(1))
        prest = _PHYSICAL.sub("", re.sub(r"\b(COMPRESS|NOCOMPRESS|LOGGING|NOLOGGING)\b", "", prest, flags=re.I)).strip()
        if prest:
            raise ValueError(f"opciones de la partición {part['name']} no soportadas: {prest[:50]}")
        pt["partitions"].append(part)
    return pt, rest


def parse_table_options(tail: str, op: dict, notes: list[str]) -> None:
    rest = tail
    m, rest = take(r"ON\s+COMMIT\s+(DELETE|PRESERVE)\s+ROWS", rest)
    if m:
        op["temporary"] = f"{m.group(1).upper()}_ROWS"
    pm = _search_top(r"PARTITION\s+BY\b", rest)
    if pm:
        op["partitioning"], after = parse_partitioning(rest[pm.start():])
        rest = (rest[:pm.start()] + " " + after).strip()
    m, rest = take(rf"TABLESPACE\s+({NAME})", rest)
    if m:
        op["tablespace"] = unq(m.group(1))
    m, rest = take(r"NOCOMPRESS\b", rest)
    if m:
        op["compression"] = "NONE"
    m, rest = take(r"(ROW\s+STORE\s+)?COMPRESS(\s+(BASIC|ADVANCED|FOR\s+OLTP))?\b", rest)
    if m:
        level = re.sub(r"\s+", " ", (m.group(3) or "BASIC").upper())
        op["compression"] = "ADVANCED" if level in ("ADVANCED", "FOR OLTP") else "BASIC"
    m, rest = take(r"(NO)?LOGGING\b", rest)
    if m:
        op["logging"] = not m.group(1)
    if _PHYSICAL.sub("", rest).strip():
        raise ValueError(f"opciones de tabla no soportadas visualmente: {_PHYSICAL.sub('', rest).strip()[:80]}")
    if rest.strip():
        notes.append("Se ignoraron opciones físicas (PCTFREE, STORAGE, PARALLEL…); se usan los valores por defecto")


# ------------------------------------------------------------------ sentencias
def custom(sql: str, schema: str, description: str, note: str | None = None) -> Imported:
    return Imported({"type": "CUSTOM_SQL", "schema": schema, "description": description[:1000], "sql": sql},
                    [note] if note else [])


def parse_create_table(stmt: str, ds: str) -> list[Imported]:
    m = re.match(rf"^CREATE\s+(GLOBAL\s+TEMPORARY\s+)?TABLE\s+{QNAME}\s*\(", stmt, re.I)
    if not m:
        raise ValueError("CREATE TABLE sin definición de columnas (p. ej. AS SELECT) no soportado visualmente")
    schema, table = qname(m.group(2), m.group(3), ds)
    end = matching_paren(stmt, m.end() - 1)
    body, tail = stmt[m.end():end], stmt[end + 1:].strip()
    if re.match(r"^AS\b", tail, re.I) or re.search(r"\bORGANIZATION\b", tail, re.I):
        raise ValueError("CREATE TABLE AS SELECT / tablas organizadas por índice no soportadas visualmente")
    notes: list[str] = []
    op: dict = {"type": "CREATE_TABLE", "schema": schema, "table": table, "comment": None, "columns": [],
                "primary_key": [], "primary_key_name": None, "constraints": []}
    if m.group(1) and not re.search(r"ON\s+COMMIT", tail, re.I):
        op["temporary"] = "DELETE_ROWS"  # valor por defecto de Oracle
    parse_table_options(tail, op, notes)
    for el in split_top(body):
        if re.match(r"^(CONSTRAINT\s|PRIMARY\s+KEY|UNIQUE\s*\(|FOREIGN\s+KEY|CHECK\s*\()", el.strip(), re.I):
            cons = [parse_table_constraint(el, notes)]
        elif re.match(r"^(SUPPLEMENTAL|PERIOD)\b", el.strip(), re.I):
            raise ValueError(f"elemento de tabla no soportado: {el[:40]}")
        else:
            col, cons = parse_column(el, notes)
            op["columns"].append(col)
        for c in cons:
            if c["kind"] == "PRIMARY_KEY":
                if not c.get("enabled", True) or c.get("deferrable") or not c.get("validated", True):
                    raise ValueError("llave primaria deshabilitada/diferible no soportada visualmente")
                op["primary_key"], op["primary_key_name"] = c["columns"], c.get("name")
                op["primary_key_tablespace"] = c.get("index_tablespace")
            else:
                op["constraints"].append(c)
    return [Imported(op, notes)]


def parse_create_index(stmt: str, ds: str) -> list[Imported]:
    m = re.match(rf"^CREATE\s+(UNIQUE\s+|BITMAP\s+)?INDEX\s+{QNAME}\s+ON\s+{QNAME}\s*", stmt, re.I)
    schema, idx = qname(m.group(2), m.group(3), ds)
    tschema, table = qname(m.group(4), m.group(5), ds)
    if tschema != schema:
        raise ValueError("índice en un esquema distinto al de la tabla")
    cols, rest = paren_body(stmt[m.end():])
    notes: list[str] = []
    op: dict = {"type": "CREATE_INDEX", "schema": schema, "table": table, "index_name": idx,
                "index_type": (m.group(1) or "NORMAL").strip().upper(), "columns": []}
    for c in split_top(cols):
        dm = re.match(r"^(.*?)\s+(ASC|DESC)$", c.strip(), re.I | re.S)
        expr, desc = (dm.group(1), dm.group(2).upper() == "DESC") if dm else (c.strip(), False)
        expr = unq(expr.strip()) if re.fullmatch(NAME, expr.strip()) else expr.strip()
        op["columns"].append({"expression": expr, "descending": desc})
    im, rest = take(rf"INDEXTYPE\s+IS\s+({NAME}(?:\s*\.\s*{NAME})?)", rest)
    if im:
        op["index_type"] = "DOMAIN"
        op["indextype"] = ".".join(unq(p.strip()) for p in im.group(1).split("."))
        pm, rest = take(r"PARAMETERS\s*\(\s*'((?:[^']|'')*)'\s*\)", rest)
        if pm:
            op["parameters"] = unescape(pm.group(1))
    if _search_top(r"GLOBAL\s+PARTITION\b", rest):
        raise ValueError("índices globales particionados no soportados visualmente")
    for pattern, key in ((r"LOCAL\b(\s*\(.*?\)(?=\s|$))?", "local"), (r"REVERSE\b", "reverse"),
                         (r"INVISIBLE\b", "invisible"), (r"ONLINE\b", "online")):
        mm, rest = take(pattern, rest)
        if mm:
            op[key] = True
            if key == "local" and mm.group(1):
                notes.append("Se ignoró la lista de particiones del índice LOCAL (Oracle las crea iguales a la tabla)")
    _, rest = take(r"VISIBLE\b", rest)
    tm, rest = take(rf"TABLESPACE\s+({NAME})", rest)
    if tm:
        op["tablespace"] = unq(tm.group(1))
    _, rest = take(r"NOCOMPRESS\b", rest)
    cm, rest = take(r"COMPRESS(\s+ADVANCED\s+(LOW|HIGH)|\s+(\d+))?\b", rest)
    if cm:
        if cm.group(2):
            op["compression"] = f"ADVANCED_{cm.group(2).upper()}"
        else:
            op["compression"] = "PREFIX"
            op["prefix_length"] = int(cm.group(3)) if cm.group(3) else None
    _, rest = take(r"(NO)?LOGGING\b", rest)
    if _PHYSICAL.sub("", rest).strip():
        raise ValueError(f"opciones de índice no soportadas visualmente: {_PHYSICAL.sub('', rest).strip()[:60]}")
    if rest.strip():
        notes.append("Se ignoraron opciones físicas del índice (PCTFREE, PARALLEL…)")
    return [Imported(op, notes)]


def parse_alter_table(stmt: str, ds: str, lookup_column) -> list[Imported] | None:
    m = re.match(rf"^ALTER\s+TABLE\s+{QNAME}\s+(.*)$", stmt, re.I | re.S)
    if not m:
        return None
    schema, table = qname(m.group(1), m.group(2), ds)
    action = m.group(3).strip()
    base = {"schema": schema, "table": table}
    up = re.sub(r"\s+", " ", action.upper())

    if re.match(r"^ADD\s+(CONSTRAINT\b|PRIMARY\s+KEY|UNIQUE\s*\(|FOREIGN\s+KEY|CHECK\s*\()", action, re.I):
        notes: list[str] = []
        return [Imported({"type": "ADD_CONSTRAINT", **base,
                          "constraint": parse_table_constraint(action[3:].strip(), notes)}, notes)]
    if re.match(r"^ADD\s+PARTITION\b", action, re.I):
        pm = re.match(rf"^ADD\s+PARTITION\s+({NAME})\s*(.*)$", action, re.I | re.S)
        part: dict = {"name": unq(pm.group(1))}
        prest = pm.group(2)
        vm = re.match(r"^\s*VALUES\s+(LESS\s+THAN\s*)?", prest, re.I)
        method = "HASH"
        if vm:
            method = "RANGE" if vm.group(1) else "LIST"
            vals, prest = paren_body(prest[vm.end():])
            part["values"] = vals.strip()
        tm, prest = take(rf"TABLESPACE\s+({NAME})", prest)
        if tm:
            part["tablespace"] = unq(tm.group(1))
        if _PHYSICAL.sub("", prest).strip():
            raise ValueError(f"opciones de partición no soportadas: {prest.strip()[:50]}")
        return [Imported({"type": "ADD_PARTITION", **base, "method": method, "partition": part})]
    if re.match(r"^ADD\b", action, re.I):
        body = action[3:].strip()
        if re.match(r"^COLUMN\b", body, re.I):
            body = body[6:].strip()
        if body.startswith("("):
            body = paren_body(body)[0]
        out = []
        for el in split_top(body):
            notes = []
            col, cons = parse_column(el, notes)
            out.append(Imported({"type": "ADD_COLUMN", **base, "column": col}, notes))
            for c in cons:
                out.append(Imported({"type": "ADD_CONSTRAINT", **base, "constraint": c}, companion=True))
        return out

    dm = re.match(rf"^DROP\s+(?:COLUMN\s+({NAME})|\(\s*({NAME})\s*\))$", action, re.I)
    if dm:
        return [Imported({"type": "DROP_COLUMN", **base, "column_name": unq(dm.group(1) or dm.group(2))})]
    dm = re.match(rf"^DROP\s+CONSTRAINT\s+({NAME})(\s+CASCADE)?(\s+(KEEP|DROP)\s+INDEX)?$", action, re.I)
    if dm:
        return [Imported({"type": "DROP_CONSTRAINT", **base, "constraint_name": unq(dm.group(1)),
                          "cascade": bool(dm.group(2)), "index": dm.group(4).upper() if dm.group(4) else None})]
    dm = re.match(rf"^DROP\s+PARTITION\s+({NAME})(\s+UPDATE\s+(GLOBAL\s+)?INDEXES)?$", action, re.I)
    if dm:
        return [Imported({"type": "DROP_PARTITION", **base, "partition_name": unq(dm.group(1)),
                          "update_indexes": bool(dm.group(2))})]
    sm = re.match(rf"^MODIFY\s+CONSTRAINT\s+({NAME})\s+(ENABLE|DISABLE)(\s+(NO)?VALIDATE)?$", action, re.I)
    if sm:
        return [Imported({"type": "CONSTRAINT_STATE", **base, "constraint_name": unq(sm.group(1)),
                          "action": sm.group(2).upper(), "validate_existing": not sm.group(4)})]
    sm = re.match(rf"^(ENABLE|DISABLE)(\s+(NO)?VALIDATE)?\s+CONSTRAINT\s+({NAME})$", action, re.I)
    if sm:
        return [Imported({"type": "CONSTRAINT_STATE", **base, "constraint_name": unq(sm.group(4)),
                          "action": sm.group(1).upper(), "validate_existing": not sm.group(3)})]
    rm = re.match(rf"^RENAME\s+(COLUMN|CONSTRAINT)\s+({NAME})\s+TO\s+({NAME})$", action, re.I)
    if rm:
        return [Imported({"type": "RENAME", "schema": schema, "target": rm.group(1).upper(), "table": table,
                          "old_name": unq(rm.group(2)), "new_name": unq(rm.group(3))})]
    rm = re.match(rf"^RENAME\s+TO\s+({NAME})$", action, re.I)
    if rm:
        return [Imported({"type": "RENAME", "schema": schema, "target": "TABLE", "old_name": table,
                          "new_name": unq(rm.group(1))})]
    if re.match(r"^(NOLOGGING|LOGGING|READ ONLY|READ WRITE|NOCOMPRESS|(ROW STORE )?COMPRESS( BASIC| ADVANCED| FOR OLTP)?)$", up):
        opt: dict = {"type": "ALTER_TABLE_OPTIONS", **base}
        if "LOGGING" in up:
            opt["logging"] = up == "LOGGING"
        elif "READ" in up:
            opt["read_only"] = up == "READ ONLY"
        else:
            opt["compression"] = "NONE" if up == "NOCOMPRESS" else ("ADVANCED" if ("ADVANCED" in up or "OLTP" in up) else "BASIC")
        return [Imported(opt)]
    if re.match(r"^MODIFY\b", action, re.I) and not re.match(
            r"^MODIFY\s+(PARTITION|LOB|DEFAULT\s+ATTRIBUTES|CONSTRAINT|PRIMARY|UNIQUE)\b", action, re.I):
        body = action[6:].strip()
        if body.startswith("("):
            body = paren_body(body)[0]
        out = []
        for el in split_top(body):
            cm = re.match(rf"\s*({NAME})\s*(.*)$", el, re.S)
            name, rest = unq(cm.group(1)), cm.group(2)
            current = lookup_column(schema, table, name)
            if current is None:
                raise ValueError(f"no existe {schema}.{table}.{name} para tomar su definición actual")
            col = dict(current)
            if rest.strip() and not _CLAUSE_KW.match(rest.strip()):
                typ, rest = parse_type(rest)
                col.update({"length": None, "precision": None, "scale": None, "char_semantics": False, **typ})
            for kw, chunk in split_clauses(rest) if rest.strip() else []:
                if kw == "DEFAULT":
                    col["default_on_null"] = bool(re.match(r"^DEFAULT\s+ON\s+NULL\b", chunk, re.I))
                    col["default"] = default_expr(chunk)
                elif kw == "NOT NULL":
                    col["nullable"] = False
                elif kw == "NULL":
                    col["nullable"] = True
                elif kw in ("INVISIBLE", "VISIBLE"):
                    col["invisible"] = kw == "INVISIBLE"
                else:
                    raise ValueError(f"cláusula {kw} en MODIFY no soportada visualmente")
            out.append(Imported({"type": "MODIFY_COLUMN", **base, "column": col, "previous": current}))
        return out
    return None


def parse_sequence(flat: str, ds: str) -> list[Imported] | None:
    m = re.match(rf"^(CREATE|ALTER) SEQUENCE {QNAME}(.*)$", flat, re.I)
    if not m:
        return None
    schema, name = qname(m.group(2), m.group(3), ds)
    create = m.group(1).upper() == "CREATE"
    op: dict = {"type": "CREATE_SEQUENCE" if create else "ALTER_SEQUENCE", "schema": schema, "name": name}
    rest = " " + m.group(4) + " "
    if not create:
        rm = re.search(r"\bRESTART(?: START WITH (-?\d+))?\b", rest, re.I)
        if rm:
            op["restart_with"] = int(rm.group(1)) if rm.group(1) else 1
            rest = rest[:rm.start()] + " " + rest[rm.end():]
    for pat, key in ((r"\bSTART WITH (-?\d+)", "start_with"), (r"\bINCREMENT BY (-?\d+)", "increment_by"),
                     (r"\bMINVALUE (-?\d+)", "min_value"), (r"\bMAXVALUE (-?\d+)", "max_value"),
                     (r"\bCACHE (\d+)", "cache")):
        mm = re.search(pat, rest, re.I)
        if mm:
            if key == "start_with" and not create:
                raise ValueError("ALTER SEQUENCE START WITH no es válido; usa RESTART START WITH")
            op[key] = int(mm.group(1))
            rest = rest[:mm.start()] + " " + rest[mm.end():]
    for word, key, value in (("NOCACHE", "cache", 0), ("NOCYCLE", "cycle", False), ("CYCLE", "cycle", True),
                             ("NOORDER", "order", False), ("ORDER", "order", True)):
        if re.search(rf"\b{word}\b", rest, re.I):
            op[key] = value
            rest = re.sub(rf"\b{word}\b", "", rest, flags=re.I)
    rest = re.sub(r"\b(NOMINVALUE|NOMAXVALUE|NOKEEP|NOSCALE|NOSHARD)\b", "", rest, flags=re.I).strip()
    if rest:
        raise ValueError(f"opciones de secuencia no soportadas: {rest[:50]}")
    return [Imported(op)]


def parse_statement(stmt_raw: str, ds: str, ops: list[Imported], lookup_column) -> list[Imported]:
    """Convierte una sentencia. `ops` son las ya convertidas (para fusionar comentarios/privilegios)."""
    stmt = strip_comments(stmt_raw).rstrip(";").strip()
    flat = re.sub(r"\s+", " ", stmt)
    up = flat.upper()

    code = re.match(rf"^CREATE\s+(?:OR\s+REPLACE\s+)?(?:FORCE\s+|NO\s+FORCE\s+)?(?:EDITIONABLE\s+|NONEDITIONABLE\s+)?"
                    rf"(PACKAGE\s+BODY|PACKAGE|PROCEDURE|FUNCTION|TRIGGER|TYPE|MATERIALIZED\s+VIEW(?!\s+LOG)|VIEW)\s+{QNAME}",
                    stmt, re.I)
    if code:
        schema, name = qname(code.group(2), code.group(3), ds)
        return [Imported({"type": "CREATE_OR_REPLACE_CODE", "schema": schema,
                          "kind": re.sub(r"\s+", " ", code.group(1).upper()), "name": name,
                          "source": stmt_raw.strip().rstrip("/").strip()})]

    if re.match(r"^CREATE (GLOBAL TEMPORARY )?TABLE ", up):
        return parse_create_table(stmt, ds)
    if re.match(r"^CREATE (UNIQUE |BITMAP )?INDEX ", up):
        return parse_create_index(stmt, ds)

    m = re.match(rf"^COMMENT ON (TABLE|MATERIALIZED VIEW) {QNAME} IS '((?:[^']|'')*)'$", flat, re.I)
    if m:
        schema, table = qname(m.group(2), m.group(3), ds)
        for it in ops:
            if it.op["type"] == "CREATE_TABLE" and (it.op["schema"], it.op["table"]) == (schema, table):
                it.op["comment"] = unescape(m.group(4))
                it.absorbed.append(stmt)
                return []
        return [Imported({"type": "SET_COMMENT", "schema": schema, "table": table, "comment": unescape(m.group(4))})]

    m = re.match(rf"^COMMENT ON COLUMN ({NAME}) ?\. ?({NAME})(?: ?\. ?({NAME}))? IS '((?:[^']|'')*)'$", flat, re.I)
    if m:
        schema, table, col = (unq(m.group(1)), unq(m.group(2)), unq(m.group(3))) if m.group(3) else (ds, unq(m.group(1)), unq(m.group(2)))
        for it in ops:
            o = it.op
            if o.get("schema") == schema and o.get("table") == table:
                cols = o["columns"] if o["type"] == "CREATE_TABLE" else [o["column"]] if o["type"] == "ADD_COLUMN" else []
                for c in cols:
                    if c["name"] == col:
                        c["comment"] = unescape(m.group(4))
                        it.absorbed.append(stmt)
                        return []
        return [Imported({"type": "SET_COMMENT", "schema": schema, "table": table, "column": col,
                          "comment": unescape(m.group(4))})]

    m = re.match(rf"^(GRANT|REVOKE) (.+?) ON {QNAME} (TO|FROM) (.+?)( WITH GRANT OPTION)?$", flat, re.I)
    if m:
        verb = m.group(1).upper()
        schema, obj = qname(m.group(3), m.group(4), ds)
        groups: dict[tuple[str, ...], list[str]] = {}
        for item in split_top(m.group(2)):
            pm = re.match(r"^([A-Za-z ]+?)\s*(\((.*)\))?$", item.strip())
            priv = re.sub(r"\s+", " ", pm.group(1).upper())
            if priv not in OBJECT_PRIVILEGES:
                return [custom(stmt, schema, f"{verb} sobre {schema}.{obj}", f"Privilegio {priv} no soportado visualmente")]
            cols = tuple(names(pm.group(3))) if pm.group(3) else ()
            groups.setdefault(cols, []).append(priv)
        out = []
        for g in split_top(m.group(6)):
            for cols, privs in groups.items():
                op = {"type": verb, "schema": schema, "object": obj, "grantee": unq(g.strip()), "privileges": privs,
                      "columns": list(cols)}
                if verb == "GRANT":
                    op["with_grant_option"] = bool(m.group(7))
                out.append(Imported(op))
        return out

    m = re.match(r"^GRANT (.+?) TO (.+?)( WITH ADMIN OPTION)?$", flat, re.I)
    if m and not m.group(3):
        grantees = [unq(g.strip()) for g in split_top(m.group(2))]
        items = [re.sub(r"\s+", " ", p.strip().upper()) for p in m.group(1).split(",")]
        targets = [it for it in ops if it.op["type"] == "CREATE_USER" and it.op["schema"] in grantees]
        valid = all(p in SYSTEM_PRIVILEGES or re.match(r"^[A-Z][A-Z0-9_$#]*$", p) for p in items)
        if len(targets) == len(grantees) and valid:
            targets[0].absorbed.append(stmt)
            for it in targets:
                for p in items:
                    if p in SYSTEM_PRIVILEGES:
                        if p not in it.op["system_privileges"]:
                            it.op["system_privileges"].append(p)
                    else:
                        it.op["roles"].append(p)
            return []
        return [custom(stmt, grantees[0], f"Privilegios de sistema para {', '.join(grantees)}")]

    m = re.match(rf"^CREATE (OR REPLACE )?(PUBLIC )?SYNONYM {QNAME} FOR {QNAME}(@\S+)?$", flat, re.I)
    if m and not m.group(7):
        owner, syn = ("PUBLIC", unq(m.group(3))) if m.group(2) else qname(m.group(3), m.group(4), ds)
        ts, to = qname(m.group(5), m.group(6), ds)
        return [Imported({"type": "CREATE_SYNONYM", "schema": owner, "synonym": syn, "target_schema": ts,
                          "target_object": to, "replace": bool(m.group(1))})]

    m = re.match(rf"^DROP (PUBLIC )?SYNONYM {QNAME}( FORCE)?$", flat, re.I)
    if m:
        owner, syn = ("PUBLIC", unq(m.group(2))) if m.group(1) else qname(m.group(2), m.group(3), ds)
        return [Imported({"type": "DROP_SYNONYM", "schema": owner, "synonym": syn})]

    m = re.match(rf"^DROP TABLE {QNAME}( CASCADE CONSTRAINTS)?( PURGE)?$", flat, re.I)
    if m:
        schema, table = qname(m.group(1), m.group(2), ds)
        return [Imported({"type": "DROP_TABLE", "schema": schema, "table": table,
                          "cascade_constraints": bool(m.group(3)), "purge": bool(m.group(4))})]

    m = re.match(rf"^DROP INDEX {QNAME}( ONLINE)?( FORCE)?$", flat, re.I)
    if m:
        schema, idx = qname(m.group(1), m.group(2), ds)
        return [Imported({"type": "DROP_INDEX", "schema": schema, "index_name": idx, "online": bool(m.group(3))})]

    m = re.match(rf"^ALTER INDEX {QNAME} (.+)$", flat, re.I)
    if m:
        schema, idx = qname(m.group(1), m.group(2), ds)
        act = m.group(3).strip()
        rn = re.match(rf"^RENAME TO ({NAME})$", act, re.I)
        if rn:
            return [Imported({"type": "RENAME", "schema": schema, "target": "INDEX", "old_name": idx, "new_name": unq(rn.group(1))})]
        if act.upper() in ("VISIBLE", "INVISIBLE"):
            return [Imported({"type": "ALTER_INDEX", "schema": schema, "index_name": idx, "action": act.upper()})]
        rb = re.match(rf"^REBUILD(?: TABLESPACE ({NAME}))?( ONLINE)?$", act, re.I)
        if rb:
            return [Imported({"type": "ALTER_INDEX", "schema": schema, "index_name": idx, "action": "REBUILD",
                              "tablespace": unq(rb.group(1)) if rb.group(1) else None, "online": bool(rb.group(2))})]

    m = re.match(rf"^RENAME ({NAME}) TO ({NAME})$", flat, re.I)
    if m and ds:
        return [Imported({"type": "RENAME", "schema": ds, "target": "TABLE", "old_name": unq(m.group(1)),
                          "new_name": unq(m.group(2))})]

    m = re.match(rf"^DROP (PROCEDURE|FUNCTION|PACKAGE BODY|PACKAGE|MATERIALIZED VIEW|VIEW|SEQUENCE|TRIGGER|TYPE) {QNAME}$", flat, re.I)
    if m:
        schema, name = qname(m.group(2), m.group(3), ds)
        return [Imported({"type": "DROP_CODE", "schema": schema, "kind": m.group(1).upper(), "name": name})]

    m = re.match(rf"^CREATE MATERIALIZED VIEW LOG ON {QNAME}(.*)$", flat, re.I)
    if m:
        schema, table = qname(m.group(1), m.group(2), ds)
        rest = m.group(3)
        op = {"type": "CREATE_MV_LOG", "schema": schema, "table": table, "with_primary_key": False}
        wm = re.search(r"\bWITH ([A-Z ,]+?)(?:\s*\(([^)]*)\))?(?= INCLUDING| EXCLUDING|$)", rest, re.I)
        if wm:
            kinds = [re.sub(r"\s+", " ", k.strip().upper()) for k in wm.group(1).split(",") if k.strip()]
            op["with_primary_key"] = "PRIMARY KEY" in kinds
            op["with_rowid"] = "ROWID" in kinds
            op["with_sequence"] = "SEQUENCE" in kinds
            if wm.group(2):
                op["columns"] = names(wm.group(2))
            rest = rest[:wm.start()] + rest[wm.end():]
        else:
            op["with_primary_key"] = True  # valor por defecto de Oracle
        if re.search(r"INCLUDING NEW VALUES", rest, re.I):
            op["including_new_values"] = True
        rest = re.sub(r"(INCLUDING|EXCLUDING) NEW VALUES", "", rest, flags=re.I)
        if _PHYSICAL.sub("", re.sub(rf"TABLESPACE {NAME}", "", rest, flags=re.I)).strip():
            raise ValueError(f"opciones del log no soportadas: {rest.strip()[:50]}")
        return [Imported(op)]

    m = re.match(rf"^DROP MATERIALIZED VIEW LOG ON {QNAME}$", flat, re.I)
    if m:
        schema, table = qname(m.group(1), m.group(2), ds)
        return [Imported({"type": "DROP_MV_LOG", "schema": schema, "table": table})]

    seq = parse_sequence(flat, ds)
    if seq is not None:
        return seq

    alt = parse_alter_table(stmt, ds, lookup_column)
    if alt is not None:
        return alt

    m = re.match(rf"^CREATE USER ({NAME}) IDENTIFIED BY (\"[^\"]*\"|\S+)(.*)$", flat, re.I)
    if m:
        user, rest = unq(m.group(1)), m.group(3)
        ts = re.search(rf"DEFAULT TABLESPACE ({NAME})", rest, re.I)
        quota = re.search(rf"QUOTA (UNLIMITED|\d+[KMG]) ON ({NAME})", rest, re.I)
        notes = ["La contraseña del script se ignora: DBA Manager genera una al ejecutar"]
        leftover = re.sub(rf"DEFAULT TABLESPACE {NAME}|QUOTA (UNLIMITED|\d+[KMG]) ON {NAME}|TEMPORARY TABLESPACE {NAME}"
                          r"|PASSWORD EXPIRE|ACCOUNT (UNLOCK|LOCK)", "", rest, flags=re.I).strip()
        if leftover:
            notes.append(f"Opciones de usuario ignoradas: {leftover[:80]}")
        return [Imported({"type": "CREATE_USER", "schema": user, "kind": "OWNER" if quota else "READER",
                          "default_tablespace": unq(ts.group(1)) if ts else (unq(quota.group(2)) if quota else None),
                          "quota": quota.group(1).upper() if quota else None,
                          "system_privileges": [], "roles": []}, notes)]

    first = re.match(rf"^\w+(?: \w+)?(?: {QNAME})?", flat)
    schema = ds
    qm = re.search(rf"\b({NAME}) ?\. ?{NAME}", flat)
    if qm:
        try:
            schema = unq(qm.group(1))
        except ValueError:
            pass
    return [custom(stmt_raw.strip(), schema, f"Importado: {first.group(0)[:80] if first else flat[:80]}",
                   "No se pudo convertir a operación visual; queda como SQL personalizado")]


def import_script(sql: str, default_schema: str, lookup_column) -> list[Imported]:
    ds = (default_schema or "").strip().upper()
    result: list[Imported] = []
    for stmt in split_script(sql):
        try:
            new = parse_statement(stmt, ds, result, lookup_column)
            for it in new:
                it.source = it.source or strip_comments(stmt).rstrip(";").strip()
            result += new
        except (ValueError, AttributeError, IndexError, TypeError) as exc:
            result.append(custom(strip_comments(stmt).rstrip(";").strip() or stmt, ds,
                                 "Importado del script", f"Queda como SQL personalizado: {exc}"))
    for it in result:
        if not it.op.get("schema"):
            it.notes.append("Sin esquema en el script: indícalo en la operación")
    return result
