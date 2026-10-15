"""Operaciones que se pueden solicitar, independientes del motor.

Cada motor (engines/*) traduce estas operaciones a su SQL. Los identificadores se validan de
forma estricta y las expresiones libres (DEFAULT, CHECK, índices por función, particiones) se
revisan para que no puedan cerrar la sentencia ni inyectar otras.
"""
import re
from typing import Annotated, Literal, Union

from pydantic import BaseModel, Field, field_validator, model_validator

IDENT_RE = re.compile(r"^[A-Za-z][A-Za-z0-9_$#]{0,127}$")


def ident(value: str) -> str:
    value = (value or "").strip()
    if not IDENT_RE.match(value):
        raise ValueError(f"Identificador inválido: '{value}' (letras, números, _ $ #; inicia con letra)")
    return value.upper()


def opt_ident(value: str | None) -> str | None:
    return ident(value) if value and value.strip() else None


def qualified(value: str) -> str:
    """ESQUEMA.NOMBRE o NOMBRE (p. ej. un indextype)."""
    return ".".join(ident(p) for p in (value or "").split("."))


def safe_expr(value: str | None, what: str = "La expresión") -> str | None:
    """Expresión SQL libre: sin ';' ni comentarios fuera de literales, comillas y paréntesis balanceados."""
    if value is None or value.strip() == "":
        return None
    v = value.strip()
    outside = re.sub(r"'(?:[^']|'')*'", "''", v)
    outside = re.sub(r'"[^"]*"', '""', outside)
    if outside.count("'") % 2 or outside.count('"') % 2:
        raise ValueError(f"{what} tiene comillas sin cerrar")
    if ";" in outside or "--" in outside or "/*" in outside:
        raise ValueError(f"{what} no puede contener ';' ni comentarios fuera de comillas")
    depth = 0
    for c in outside:
        depth += c == "("
        depth -= c == ")"
        if depth < 0:
            break
    if depth != 0:
        raise ValueError(f"{what} tiene paréntesis desbalanceados")
    return v


class _Model(BaseModel):
    model_config = {"extra": "forbid", "populate_by_name": True}


class _Op(_Model):
    @property
    def schema_name(self) -> str:  # esquema afectado (para alcance de aprobadores)
        return getattr(self, "schema_")

    @property
    def object_name(self) -> str | None:
        return getattr(self, "table", None)

    @property
    def summary(self) -> str:
        return self.__class__.__doc__ or ""


# ------------------------------------------------------------------ columnas
class Column(_Model):
    name: str
    data_type: str | None = None  # opcional solo en columnas virtuales (Oracle lo deduce)
    length: int | None = Field(None, ge=1, le=32767)
    char_semantics: bool = False  # VARCHAR2(n CHAR)
    precision: int | None = Field(None, ge=-1, le=126)
    scale: int | None = Field(None, ge=-84, le=127)
    nullable: bool = True
    default: str | None = None
    default_on_null: bool = False
    comment: str | None = Field(None, max_length=4000)
    identity: bool = False
    identity_mode: Literal["BY_DEFAULT_ON_NULL", "BY_DEFAULT", "ALWAYS"] = "BY_DEFAULT_ON_NULL"
    identity_start: int | None = None
    identity_increment: int | None = None
    virtual_expression: str | None = None  # GENERATED ALWAYS AS (expr) VIRTUAL
    invisible: bool = False

    v_n = field_validator("name")(lambda cls, v: ident(v))
    v_def = field_validator("default")(lambda cls, v: safe_expr(v, "El valor por defecto"))
    v_virt = field_validator("virtual_expression")(lambda cls, v: safe_expr(v, "La expresión virtual"))

    @field_validator("data_type")
    @classmethod
    def v_dt(cls, v: str | None) -> str | None:
        if v is None or not v.strip():
            return None
        v = v.strip().upper()
        if not re.match(r"^[A-Z][A-Z0-9_ ]{0,40}$", v):
            raise ValueError(f"Tipo de dato inválido: {v}")
        return v

    @model_validator(mode="after")
    def v_combo(self):
        if self.data_type is None and not self.virtual_expression:
            raise ValueError(f"{self.name}: indica el tipo de dato")
        if self.virtual_expression and (self.identity or self.default is not None):
            raise ValueError(f"{self.name}: una columna virtual no puede tener DEFAULT ni ser identidad")
        if self.identity and self.default is not None:
            raise ValueError(f"{self.name}: una columna identidad no lleva DEFAULT")
        if self.default_on_null and self.default is None:
            raise ValueError(f"{self.name}: DEFAULT ON NULL requiere un valor por defecto")
        return self


# ------------------------------------------------------------------ restricciones
class ConstraintDef(_Model):
    kind: Literal["PRIMARY_KEY", "UNIQUE", "FOREIGN_KEY", "CHECK"]
    name: str | None = None
    columns: list[str] = []
    ref_schema: str | None = None
    ref_table: str | None = None
    ref_columns: list[str] = []
    on_delete: Literal["NO_ACTION", "CASCADE", "SET_NULL"] = "NO_ACTION"
    condition: str | None = None
    deferrable: bool = False
    initially_deferred: bool = False
    enabled: bool = True
    validated: bool = True
    index_tablespace: str | None = None  # USING INDEX TABLESPACE (PK/UNIQUE)

    v_n = field_validator("name", "ref_schema", "ref_table", "index_tablespace")(lambda cls, v: opt_ident(v))
    v_c = field_validator("columns", "ref_columns")(lambda cls, v: [ident(c) for c in v])
    v_cond = field_validator("condition")(lambda cls, v: safe_expr(v, "La condición CHECK"))

    @model_validator(mode="after")
    def v_kind(self):
        if self.kind == "CHECK":
            if not self.condition:
                raise ValueError("Una restricción CHECK requiere condición")
        elif not self.columns:
            raise ValueError("La restricción requiere columnas")
        if self.kind == "FOREIGN_KEY":
            if not self.ref_table:
                raise ValueError("La llave foránea requiere la tabla referenciada")
            if self.ref_columns and len(self.ref_columns) != len(self.columns):
                raise ValueError("La llave foránea debe tener el mismo número de columnas que las referenciadas")
        if self.initially_deferred and not self.deferrable:
            raise ValueError("INITIALLY DEFERRED requiere DEFERRABLE")
        return self


# ------------------------------------------------------------------ particiones
class PartitionDef(_Model):
    name: str
    values: str | None = None  # RANGE: límite (LESS THAN); LIST: lista de valores; HASH: no aplica
    tablespace: str | None = None
    v_n = field_validator("name")(lambda cls, v: ident(v))
    v_ts = field_validator("tablespace")(lambda cls, v: opt_ident(v))
    v_v = field_validator("values")(lambda cls, v: safe_expr(v, "Los valores de la partición"))


class Partitioning(_Model):
    method: Literal["RANGE", "LIST", "HASH"]
    columns: list[str] = Field(min_length=1)
    interval: str | None = None  # solo RANGE, p. ej. NUMTOYMINTERVAL(1,'MONTH')
    partitions: list[PartitionDef] = []
    hash_count: int | None = Field(None, ge=1, le=1024)
    v_c = field_validator("columns")(lambda cls, v: [ident(c) for c in v])
    v_i = field_validator("interval")(lambda cls, v: safe_expr(v, "El intervalo"))

    @model_validator(mode="after")
    def v_method(self):
        if self.interval and (self.method != "RANGE" or len(self.columns) != 1):
            raise ValueError("INTERVAL solo aplica a particionado RANGE de una columna")
        if self.method in ("RANGE", "LIST"):
            if not self.partitions:
                raise ValueError(f"El particionado {self.method} requiere al menos una partición")
            if any(not p.values for p in self.partitions):
                raise ValueError("Cada partición RANGE/LIST requiere sus valores")
        elif not self.partitions and not self.hash_count:
            raise ValueError("El particionado HASH requiere número de particiones o sus nombres")
        return self


# ------------------------------------------------------------------ tablas
class CreateTable(_Op):
    """Crear tabla"""

    type: Literal["CREATE_TABLE"]
    schema_: str = Field(alias="schema")
    table: str
    comment: str | None = Field(None, max_length=4000)
    columns: list[Column] = Field(min_length=1)
    primary_key: list[str] = []
    primary_key_name: str | None = None
    primary_key_tablespace: str | None = None  # USING INDEX TABLESPACE de la PK
    constraints: list[ConstraintDef] = []  # UNIQUE, FOREIGN_KEY, CHECK
    tablespace: str | None = None
    temporary: Literal["DELETE_ROWS", "PRESERVE_ROWS"] | None = None  # GLOBAL TEMPORARY ... ON COMMIT
    compression: Literal["NONE", "BASIC", "ADVANCED"] | None = None
    logging: bool | None = None
    partitioning: Partitioning | None = None

    v_s = field_validator("schema_", "table")(lambda cls, v: ident(v))
    v_ts = field_validator("tablespace", "primary_key_name", "primary_key_tablespace")(lambda cls, v: opt_ident(v))
    v_pk = field_validator("primary_key")(lambda cls, v: [ident(c) for c in v])

    @model_validator(mode="after")
    def v_check(self):
        names = [c.name for c in self.columns]
        if len(names) != len(set(names)):
            raise ValueError("Columnas duplicadas")
        missing = set(self.primary_key) - set(names)
        for c in self.constraints:
            if c.kind == "PRIMARY_KEY":
                raise ValueError("La llave primaria se define con 'primary_key', no en 'constraints'")
            missing |= set(c.columns) - set(names)
        if self.partitioning:
            missing |= set(self.partitioning.columns) - set(names)
        if missing:
            raise ValueError(f"Columnas inexistentes en restricciones/particiones: {', '.join(sorted(missing))}")
        if self.temporary and (self.partitioning or self.tablespace or self.compression):
            raise ValueError("Una tabla temporal no admite particiones, tablespace ni compresión")
        return self


class AddColumn(_Op):
    """Agregar columna"""

    type: Literal["ADD_COLUMN"]
    schema_: str = Field(alias="schema")
    table: str
    column: Column
    v_s = field_validator("schema_", "table")(lambda cls, v: ident(v))


class ModifyColumn(_Op):
    """Modificar columna"""

    type: Literal["MODIFY_COLUMN"]
    schema_: str = Field(alias="schema")
    table: str
    column: Column
    previous: Column | None = Field(None, description="Definición actual (del catálogo) para generar solo el cambio")
    v_s = field_validator("schema_", "table")(lambda cls, v: ident(v))


class DropColumn(_Op):
    """Eliminar columna"""

    type: Literal["DROP_COLUMN"]
    schema_: str = Field(alias="schema")
    table: str
    column_name: str
    v_s = field_validator("schema_", "table", "column_name")(lambda cls, v: ident(v))


class DropTable(_Op):
    """Eliminar tabla"""

    type: Literal["DROP_TABLE"]
    schema_: str = Field(alias="schema")
    table: str
    cascade_constraints: bool = False
    purge: bool = False
    v_s = field_validator("schema_", "table")(lambda cls, v: ident(v))


class AlterTableOptions(_Op):
    """Cambiar opciones de tabla (logging, compresión, solo lectura)"""

    type: Literal["ALTER_TABLE_OPTIONS"]
    schema_: str = Field(alias="schema")
    table: str
    logging: bool | None = None
    compression: Literal["NONE", "BASIC", "ADVANCED"] | None = None
    read_only: bool | None = None
    v_s = field_validator("schema_", "table")(lambda cls, v: ident(v))

    @model_validator(mode="after")
    def v_any(self):
        if self.logging is None and self.compression is None and self.read_only is None:
            raise ValueError("Indica al menos una opción a cambiar")
        return self


class Rename(_Op):
    """Renombrar tabla, columna, restricción o índice"""

    type: Literal["RENAME"]
    schema_: str = Field(alias="schema")
    target: Literal["TABLE", "COLUMN", "CONSTRAINT", "INDEX"]
    table: str | None = None  # requerida salvo para INDEX
    old_name: str
    new_name: str
    v_s = field_validator("schema_", "old_name", "new_name")(lambda cls, v: ident(v))
    v_t = field_validator("table")(lambda cls, v: opt_ident(v))

    @model_validator(mode="after")
    def v_table(self):
        if self.target == "TABLE":
            self.table = self.old_name
        if self.target in ("COLUMN", "CONSTRAINT") and not self.table:
            raise ValueError("Indica la tabla")
        if self.old_name == self.new_name:
            raise ValueError("El nombre nuevo es igual al actual")
        return self

    @property
    def object_name(self):
        return self.table or self.old_name


class SetComment(_Op):
    """Comentario de tabla o columna existente"""

    type: Literal["SET_COMMENT"]
    schema_: str = Field(alias="schema")
    table: str
    column: str | None = None
    comment: str = Field("", max_length=4000)
    v_s = field_validator("schema_", "table")(lambda cls, v: ident(v))
    v_c = field_validator("column")(lambda cls, v: opt_ident(v))


# ------------------------------------------------------------------ restricciones sobre tablas existentes
class AddConstraint(_Op):
    """Agregar restricción (PK, única, foránea, check)"""

    type: Literal["ADD_CONSTRAINT"]
    schema_: str = Field(alias="schema")
    table: str
    constraint: ConstraintDef
    v_s = field_validator("schema_", "table")(lambda cls, v: ident(v))


class DropConstraint(_Op):
    """Eliminar restricción"""

    type: Literal["DROP_CONSTRAINT"]
    schema_: str = Field(alias="schema")
    table: str
    constraint_name: str
    cascade: bool = False
    index: Literal["KEEP", "DROP"] | None = None  # para PK/UNIQUE
    v_s = field_validator("schema_", "table", "constraint_name")(lambda cls, v: ident(v))


class ConstraintState(_Op):
    """Habilitar o deshabilitar restricción"""

    type: Literal["CONSTRAINT_STATE"]
    schema_: str = Field(alias="schema")
    table: str
    constraint_name: str
    action: Literal["ENABLE", "DISABLE"]
    validate_existing: bool = True  # ENABLE VALIDATE / NOVALIDATE
    v_s = field_validator("schema_", "table", "constraint_name")(lambda cls, v: ident(v))


# ------------------------------------------------------------------ índices
class IndexColumn(_Model):
    expression: str  # nombre de columna o expresión (índice por función)
    descending: bool = False

    @field_validator("expression")
    @classmethod
    def v_e(cls, v: str) -> str:
        v = (v or "").strip()
        if IDENT_RE.match(v):
            return v.upper()
        return safe_expr(v, "La expresión del índice") or ""

    @property
    def is_column(self) -> bool:
        return bool(IDENT_RE.match(self.expression))


class CreateIndex(_Op):
    """Crear índice"""

    type: Literal["CREATE_INDEX"]
    schema_: str = Field(alias="schema")
    table: str
    index_name: str
    index_type: Literal["NORMAL", "UNIQUE", "BITMAP", "DOMAIN"] = "NORMAL"
    columns: list[IndexColumn] = Field(min_length=1)
    reverse: bool = False
    compression: Literal["PREFIX", "ADVANCED_LOW", "ADVANCED_HIGH"] | None = None
    prefix_length: int | None = Field(None, ge=1, le=32)
    tablespace: str | None = None
    local: bool = False  # índice particionado local (tabla particionada)
    invisible: bool = False
    online: bool = False
    indextype: str | None = None  # DOMAIN: p. ej. CTXSYS.CONTEXT
    parameters: str | None = Field(None, max_length=1000)  # DOMAIN: PARAMETERS('...')
    v_s = field_validator("schema_", "table", "index_name")(lambda cls, v: ident(v))
    v_ts = field_validator("tablespace")(lambda cls, v: opt_ident(v))
    v_it = field_validator("indextype")(lambda cls, v: qualified(v) if v else None)

    @model_validator(mode="before")
    @classmethod
    def v_legacy(cls, data):
        # Formato anterior: columns = ["A", "B"], unique = true
        if isinstance(data, dict):
            data = dict(data)
            if data.get("columns") and isinstance(data["columns"][0], str):
                data["columns"] = [{"expression": c} for c in data["columns"]]
            if "unique" in data:
                if data.pop("unique") and data.get("index_type", "NORMAL") == "NORMAL":
                    data["index_type"] = "UNIQUE"
        return data

    @model_validator(mode="after")
    def v_combo(self):
        if self.index_type == "DOMAIN":
            if not self.indextype:
                raise ValueError("Un índice de dominio requiere INDEXTYPE (p. ej. CTXSYS.CONTEXT)")
            if len(self.columns) != 1 or not self.columns[0].is_column or self.columns[0].descending:
                raise ValueError("Un índice de dominio va sobre una sola columna, sin DESC")
            if self.reverse or self.compression or self.local or self.online:
                raise ValueError("Un índice de dominio no admite REVERSE, compresión, LOCAL ni ONLINE")
        elif self.indextype or self.parameters:
            raise ValueError("INDEXTYPE/PARAMETERS solo aplican a índices de dominio")
        if self.index_type == "BITMAP" and self.reverse:
            raise ValueError("Un índice BITMAP no puede ser REVERSE")
        if self.reverse and any(not c.is_column or c.descending for c in self.columns):
            raise ValueError("REVERSE no admite expresiones ni columnas DESC")
        max_prefix = len(self.columns) - (1 if self.index_type == "UNIQUE" else 0)
        if self.compression == "PREFIX" and self.prefix_length and self.prefix_length > max_prefix:
            raise ValueError(f"La longitud de prefijo de compresión no puede ser mayor a {max_prefix}")
        if self.compression == "PREFIX" and self.index_type == "UNIQUE" and len(self.columns) < 2:
            raise ValueError("Un índice único de una sola columna no se puede comprimir por prefijo")
        if self.compression and self.compression != "PREFIX" and self.index_type == "BITMAP":
            raise ValueError("La compresión avanzada no aplica a índices BITMAP")
        return self

    @property
    def object_name(self):
        return self.index_name


class DropIndex(_Op):
    """Eliminar índice"""

    type: Literal["DROP_INDEX"]
    schema_: str = Field(alias="schema")
    index_name: str
    table: str | None = None  # informativo
    online: bool = False
    v_s = field_validator("schema_", "index_name")(lambda cls, v: ident(v))
    v_t = field_validator("table")(lambda cls, v: opt_ident(v))

    @property
    def object_name(self):
        return self.index_name


class AlterIndex(_Op):
    """Reconstruir índice o cambiar su visibilidad"""

    type: Literal["ALTER_INDEX"]
    schema_: str = Field(alias="schema")
    index_name: str
    table: str | None = None
    action: Literal["REBUILD", "VISIBLE", "INVISIBLE"]
    online: bool = False  # REBUILD ONLINE
    tablespace: str | None = None  # REBUILD TABLESPACE
    v_s = field_validator("schema_", "index_name")(lambda cls, v: ident(v))
    v_t = field_validator("table", "tablespace")(lambda cls, v: opt_ident(v))

    @property
    def object_name(self):
        return self.index_name


# ------------------------------------------------------------------ particiones de tablas existentes
class AddPartition(_Op):
    """Agregar partición"""

    type: Literal["ADD_PARTITION"]
    schema_: str = Field(alias="schema")
    table: str
    method: Literal["RANGE", "LIST", "HASH"]
    partition: PartitionDef
    v_s = field_validator("schema_", "table")(lambda cls, v: ident(v))

    @model_validator(mode="after")
    def v_vals(self):
        if self.method != "HASH" and not self.partition.values:
            raise ValueError("La partición requiere sus valores")
        return self


class DropPartition(_Op):
    """Eliminar partición (y sus datos)"""

    type: Literal["DROP_PARTITION"]
    schema_: str = Field(alias="schema")
    table: str
    partition_name: str
    update_indexes: bool = True
    v_s = field_validator("schema_", "table", "partition_name")(lambda cls, v: ident(v))


# ------------------------------------------------------------------ secuencias y logs de vistas materializadas
class CreateSequence(_Op):
    """Crear secuencia"""

    type: Literal["CREATE_SEQUENCE"]
    schema_: str = Field(alias="schema")
    name: str
    start_with: int | None = None
    increment_by: int | None = None
    min_value: int | None = None
    max_value: int | None = None
    cache: int | None = Field(None, ge=0)  # 0 = NOCACHE
    cycle: bool = False
    order: bool = False
    v_s = field_validator("schema_", "name")(lambda cls, v: ident(v))

    @property
    def object_name(self):
        return self.name


class AlterSequence(_Op):
    """Modificar secuencia"""

    type: Literal["ALTER_SEQUENCE"]
    schema_: str = Field(alias="schema")
    name: str
    restart_with: int | None = None
    increment_by: int | None = None
    min_value: int | None = None
    max_value: int | None = None
    cache: int | None = Field(None, ge=0)
    cycle: bool | None = None
    order: bool | None = None
    v_s = field_validator("schema_", "name")(lambda cls, v: ident(v))

    @property
    def object_name(self):
        return self.name


class CreateMvLog(_Op):
    """Crear log de vista materializada"""

    type: Literal["CREATE_MV_LOG"]
    schema_: str = Field(alias="schema")
    table: str
    with_primary_key: bool = True
    with_rowid: bool = False
    with_sequence: bool = False
    columns: list[str] = []
    including_new_values: bool = False
    v_s = field_validator("schema_", "table")(lambda cls, v: ident(v))
    v_c = field_validator("columns")(lambda cls, v: [ident(c) for c in v])

    @model_validator(mode="after")
    def v_with(self):
        if not (self.with_primary_key or self.with_rowid or self.with_sequence or self.columns):
            raise ValueError("Indica PRIMARY KEY, ROWID, SEQUENCE o columnas")
        return self


class DropMvLog(_Op):
    """Eliminar log de vista materializada"""

    type: Literal["DROP_MV_LOG"]
    schema_: str = Field(alias="schema")
    table: str
    v_s = field_validator("schema_", "table")(lambda cls, v: ident(v))


# ------------------------------------------------------------------ permisos y sinónimos
OBJECT_PRIVILEGES = ["SELECT", "INSERT", "UPDATE", "DELETE", "EXECUTE", "REFERENCES", "ALTER", "INDEX", "READ",
                     "ON COMMIT REFRESH", "QUERY REWRITE", "DEBUG", "FLASHBACK"]
COLUMN_PRIVILEGES = {"INSERT", "UPDATE", "REFERENCES"}


class Grant(_Op):
    """Otorgar permisos sobre objeto"""

    type: Literal["GRANT"]
    schema_: str = Field(alias="schema")
    object: str
    grantee: str
    privileges: list[str] = Field(min_length=1)
    columns: list[str] = []  # permisos por columna (INSERT/UPDATE/REFERENCES)
    with_grant_option: bool = False
    v_s = field_validator("schema_", "object", "grantee")(lambda cls, v: ident(v))
    v_c = field_validator("columns")(lambda cls, v: [ident(c) for c in v])

    @field_validator("privileges")
    @classmethod
    def v_p(cls, v: list[str]) -> list[str]:
        v = sorted({re.sub(r"\s+", " ", p.strip().upper()) for p in v})
        bad = [p for p in v if p not in OBJECT_PRIVILEGES]
        if bad:
            raise ValueError(f"Privilegios no permitidos: {', '.join(bad)}")
        return v

    @model_validator(mode="after")
    def v_cols(self):
        if self.columns and not set(self.privileges) <= COLUMN_PRIVILEGES:
            raise ValueError("Por columna solo se otorgan INSERT, UPDATE o REFERENCES")
        return self

    @property
    def object_name(self):
        return self.object


class Revoke(Grant):
    """Revocar permisos sobre objeto"""

    type: Literal["REVOKE"]  # type: ignore[assignment]

    @model_validator(mode="after")
    def v_no_cols(self):
        if self.columns:
            raise ValueError("Oracle revoca a nivel tabla; quita las columnas")
        return self


class CreateSynonym(_Op):
    """Crear sinónimo"""

    type: Literal["CREATE_SYNONYM"]
    schema_: str = Field(alias="schema", description="Dueño del sinónimo o PUBLIC")
    synonym: str
    target_schema: str
    target_object: str
    replace: bool = True
    v_s = field_validator("schema_", "synonym", "target_schema", "target_object")(lambda cls, v: ident(v))

    @property
    def object_name(self):
        return self.synonym


class DropSynonym(_Op):
    """Eliminar sinónimo"""

    type: Literal["DROP_SYNONYM"]
    schema_: str = Field(alias="schema")
    synonym: str
    v_s = field_validator("schema_", "synonym")(lambda cls, v: ident(v))

    @property
    def object_name(self):
        return self.synonym


SYSTEM_PRIVILEGES = ["CREATE SESSION", "CREATE SYNONYM", "CREATE VIEW", "CREATE TABLE", "CREATE SEQUENCE",
                     "CREATE PROCEDURE", "CREATE TRIGGER", "CREATE TYPE", "CREATE MATERIALIZED VIEW"]


class CreateUser(_Op):
    """Crear usuario (lector u owner)"""

    type: Literal["CREATE_USER"]
    schema_: str = Field(alias="schema", description="Nombre del usuario a crear")
    kind: Literal["READER", "OWNER"] = "READER"
    default_tablespace: str | None = None
    quota: str | None = Field(None, pattern=r"^(UNLIMITED|\d{1,6}[KMG])$")
    system_privileges: list[str] = ["CREATE SESSION"]
    roles: list[str] = []
    v_s = field_validator("schema_")(lambda cls, v: ident(v))
    v_ts = field_validator("default_tablespace")(lambda cls, v: opt_ident(v))
    v_r = field_validator("roles")(lambda cls, v: [ident(r) for r in v])

    @field_validator("system_privileges")
    @classmethod
    def v_sp(cls, v: list[str]) -> list[str]:
        v = [p.strip().upper() for p in v]
        bad = [p for p in v if p not in SYSTEM_PRIVILEGES]
        if bad:
            raise ValueError(f"Privilegios de sistema no permitidos: {', '.join(bad)}")
        return v

    @property
    def object_name(self):
        return self.schema_


# ------------------------------------------------------------------ código (procedimientos, vistas, triggers, ...)
CODE_KINDS = ["PROCEDURE", "FUNCTION", "PACKAGE", "PACKAGE BODY", "TRIGGER", "VIEW", "MATERIALIZED VIEW", "TYPE"]
CodeKind = Literal["PROCEDURE", "FUNCTION", "PACKAGE", "PACKAGE BODY", "TRIGGER", "VIEW", "MATERIALIZED VIEW", "TYPE"]


class CreateOrReplaceCode(_Op):
    """Crear o actualizar procedimiento/función/paquete/vista/trigger"""

    type: Literal["CREATE_OR_REPLACE_CODE"]
    schema_: str = Field(alias="schema")
    kind: CodeKind
    name: str
    source: str = Field(min_length=10, max_length=200_000,
                        description="Código completo a partir de CREATE [OR REPLACE] ...")
    v_s = field_validator("schema_", "name")(lambda cls, v: ident(v))

    @model_validator(mode="after")
    def v_check_header(self):
        src = self.source.strip().rstrip("/").strip()
        header = re.match(
            r"^CREATE\s+(?P<replace>OR\s+REPLACE\s+)?(?:(?:EDITIONABLE|NONEDITIONABLE|FORCE|NO\s+FORCE)\s+)*"
            r"(?P<kind>PACKAGE\s+BODY|PACKAGE|PROCEDURE|FUNCTION|TRIGGER|MATERIALIZED\s+VIEW|VIEW|TYPE)\s+"
            r"(?:\"?(?P<owner>[A-Za-z][\w$#]*)\"?\s*\.\s*)?(?P<q>\"?)(?P<name>[A-Za-z][\w$#]*)\"?",
            src, re.IGNORECASE,
        )
        if not header:
            raise ValueError("El código debe iniciar con CREATE [OR REPLACE] <TIPO> <ESQUEMA>.<NOMBRE>")
        kind = re.sub(r"\s+", " ", header.group("kind").upper())
        owner, name = (header.group("owner") or "").upper(), header.group("name").upper()
        if kind != self.kind:
            raise ValueError(f"El código declara {kind} pero la operación es {self.kind}")
        if kind == "MATERIALIZED VIEW" and header.group("replace"):
            raise ValueError("Oracle no admite CREATE OR REPLACE MATERIALIZED VIEW; usa CREATE MATERIALIZED VIEW")
        if owner and owner != self.schema_:
            raise ValueError(f"El código crea en el esquema {owner} pero la operación es para {self.schema_}")
        if name != self.name:
            raise ValueError(f"El código crea {name} pero la operación es para {self.name}")
        if not owner:
            # Sin esquema explícito se crearía en la cuenta ejecutora: se califica siempre.
            idx = header.start("q")
            src = f"{src[:idx]}{self.schema_}.{src[idx:]}"
        self.source = src
        return self

    @property
    def object_name(self):
        return self.name


class DropCode(_Op):
    """Eliminar procedimiento/función/paquete/vista/secuencia/trigger"""

    type: Literal["DROP_CODE"]
    schema_: str = Field(alias="schema")
    kind: Literal["PROCEDURE", "FUNCTION", "PACKAGE", "PACKAGE BODY", "TRIGGER", "VIEW", "MATERIALIZED VIEW",
                  "TYPE", "SEQUENCE"]
    name: str
    v_s = field_validator("schema_", "name")(lambda cls, v: ident(v))

    @property
    def object_name(self):
        return self.name


class CustomSql(_Op):
    """SQL personalizado (revisión manual estricta)"""

    type: Literal["CUSTOM_SQL"]
    schema_: str = Field(alias="schema", description="Esquema principal afectado")
    description: str = Field(min_length=5, max_length=1000)
    sql: str = Field(min_length=5, max_length=200_000)
    v_s = field_validator("schema_")(lambda cls, v: ident(v))

    @property
    def object_name(self):
        return None


Operation = Annotated[
    Union[CreateTable, AddColumn, ModifyColumn, DropColumn, DropTable, AlterTableOptions, Rename, SetComment,
          AddConstraint, DropConstraint, ConstraintState, CreateIndex, DropIndex, AlterIndex, AddPartition,
          DropPartition, CreateSequence, AlterSequence, CreateMvLog, DropMvLog, Grant, Revoke, CreateSynonym,
          DropSynonym, CreateUser, CreateOrReplaceCode, DropCode, CustomSql],
    Field(discriminator="type"),
]

OPERATION_LABELS = {
    "CREATE_TABLE": "Crear tabla",
    "ADD_COLUMN": "Agregar columna",
    "MODIFY_COLUMN": "Modificar columna",
    "DROP_COLUMN": "Eliminar columna",
    "RENAME": "Renombrar (tabla, columna, restricción, índice)",
    "SET_COMMENT": "Comentario de tabla/columna",
    "ALTER_TABLE_OPTIONS": "Opciones de tabla (logging, compresión, solo lectura)",
    "DROP_TABLE": "Eliminar tabla",
    "ADD_CONSTRAINT": "Agregar restricción (PK, única, foránea, check)",
    "DROP_CONSTRAINT": "Eliminar restricción",
    "CONSTRAINT_STATE": "Habilitar/deshabilitar restricción",
    "CREATE_INDEX": "Crear índice",
    "ALTER_INDEX": "Reconstruir índice / cambiar visibilidad",
    "DROP_INDEX": "Eliminar índice",
    "ADD_PARTITION": "Agregar partición",
    "DROP_PARTITION": "Eliminar partición",
    "CREATE_SEQUENCE": "Crear secuencia",
    "ALTER_SEQUENCE": "Modificar secuencia",
    "CREATE_MV_LOG": "Crear log de vista materializada",
    "DROP_MV_LOG": "Eliminar log de vista materializada",
    "GRANT": "Otorgar permisos",
    "REVOKE": "Revocar permisos",
    "CREATE_SYNONYM": "Crear sinónimo",
    "DROP_SYNONYM": "Eliminar sinónimo",
    "CREATE_USER": "Crear usuario",
    "CREATE_OR_REPLACE_CODE": "Crear/actualizar código (procedimiento, vista, trigger, vista materializada…)",
    "DROP_CODE": "Eliminar código/vista/secuencia/trigger",
    "CUSTOM_SQL": "SQL personalizado",
}
DESTRUCTIVE_OPS = {"DROP_TABLE", "DROP_COLUMN", "DROP_SYNONYM", "DROP_CODE", "REVOKE", "CUSTOM_SQL", "MODIFY_COLUMN",
                   "DROP_CONSTRAINT", "CONSTRAINT_STATE", "DROP_INDEX", "DROP_PARTITION", "DROP_MV_LOG", "RENAME",
                   "ALTER_TABLE_OPTIONS"}
