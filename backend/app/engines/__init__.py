"""Registro de motores. Para agregar uno (PostgreSQL, SQL Server, ...) implementar EngineAdapter
y registrarlo aquí."""
from app.engines.base import ConnInfo, EngineAdapter
from app.engines.oracle import OracleAdapter

ENGINES: dict[str, EngineAdapter] = {
    OracleAdapter.key: OracleAdapter(),
}


def get_engine(key: str) -> EngineAdapter:
    try:
        return ENGINES[key]
    except KeyError as exc:
        raise ValueError(f"Motor no soportado: {key}") from exc


__all__ = ["ENGINES", "ConnInfo", "EngineAdapter", "get_engine"]
