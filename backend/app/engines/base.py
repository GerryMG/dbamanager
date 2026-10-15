"""Contrato que implementa cada motor de base de datos administrado."""
from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from typing import Any


@dataclass
class ConnInfo:
    host: str
    port: int
    service_name: str
    user: str
    password: str
    # Usuario de DBA Manager que origina la sesión (se propaga como client_identifier/auditoría)
    app_user: str | None = None
    excluded_schemas: set[str] = field(default_factory=set)


@dataclass
class Statement:
    sql: str
    plsql: bool = False  # en scripts se termina con "/" en lugar de ";"
    secret_placeholder: str | None = None  # p. ej. password generada al ejecutar

    def to_dict(self) -> dict:
        return {"sql": self.sql, "plsql": self.plsql, "secret_placeholder": self.secret_placeholder}

    @classmethod
    def from_dict(cls, d: dict) -> "Statement":
        return cls(**d)


@dataclass
class Finding:
    level: str  # error | warning | info
    message: str
    op_seq: int | None = None


@dataclass
class StatementResult:
    sql: str
    ok: bool
    message: str = ""
    secret: str | None = None


class EngineAdapter(ABC):
    key: str
    label: str
    data_types: list[dict[str, Any]]

    # ---- conexión ----
    @abstractmethod
    def test_connection(self, info: ConnInfo) -> dict: ...

    # ---- exploración (solo diccionario, nunca datos) ----
    @abstractmethod
    def list_schemas(self, info: ConnInfo) -> list[dict]: ...

    @abstractmethod
    def list_objects(self, info: ConnInfo, schema: str) -> list[dict]: ...

    @abstractmethod
    def describe_table(self, info: ConnInfo, schema: str, name: str) -> dict: ...

    @abstractmethod
    def describe_user(self, info: ConnInfo, schema: str) -> dict: ...

    @abstractmethod
    def get_ddl(self, info: ConnInfo, schema: str, object_type: str, name: str) -> str: ...

    @abstractmethod
    def search(self, info: ConnInfo, text: str) -> list[dict]: ...

    # ---- generación y ejecución ----
    @abstractmethod
    def render(self, op, version: str | None = None) -> list[Statement]:
        """Genera el SQL; `version` es la del servidor y restringe la sintaxis permitida."""

    def server_version(self, info: ConnInfo) -> str | None:
        return None

    @abstractmethod
    def preflight(self, info: ConnInfo, ops: list) -> list[Finding]: ...

    @abstractmethod
    def execute(self, info: ConnInfo, statements: list[Statement], tag: str) -> list[StatementResult]: ...

    def import_script(self, info: ConnInfo, sql: str, default_schema: str) -> list[dict]:
        """Convierte un script SQL en operaciones ({op, notes}). Por defecto no soportado."""
        raise ValueError(f"Importar scripts no está disponible para {self.label}")

    def script(self, statements: list[Statement]) -> str:
        out = []
        for st in statements:
            sql = st.sql.rstrip()
            out.append(f"{sql}\n/" if st.plsql else f"{sql};")
        return "\n\n".join(out)
