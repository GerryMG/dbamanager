from fastapi import HTTPException
from sqlalchemy.orm import Session

from app.core.crypto import decrypt
from app.engines import ConnInfo, EngineAdapter, get_engine
from app.models import DbConnection


def load_connection(db: Session, connection_id: int, active_only: bool = True) -> DbConnection:
    conn = db.get(DbConnection, connection_id)
    if conn is None or (active_only and not conn.active):
        raise HTTPException(404, "Conexión no encontrada")
    return conn


def conn_info(conn: DbConnection, role: str, app_user: str | None = None) -> ConnInfo:
    """role = 'catalog' (solo diccionario) o 'exec' (cuenta privilegiada)."""
    if role == "exec":
        if not conn.exec_user or not conn.exec_password_enc:
            raise HTTPException(409, "La conexión no tiene cuenta ejecutora configurada")
        user, password = conn.exec_user, decrypt(conn.exec_password_enc)
    else:
        user, password = conn.catalog_user, decrypt(conn.catalog_password_enc)
    service_accounts = {conn.catalog_user.upper()} | ({conn.exec_user.upper()} if conn.exec_user else set())
    return ConnInfo(
        host=conn.host, port=conn.port, service_name=conn.service_name, user=user, password=password or "",
        app_user=app_user, excluded_schemas=service_accounts,
    )


def engine_for(conn: DbConnection) -> EngineAdapter:
    return get_engine(conn.engine)


def server_version(db: Session, conn: DbConnection) -> str | None:
    """Versión del servidor: se detecta una vez por conexión y se guarda en la metadata."""
    if conn.server_version:
        return conn.server_version
    try:
        version = engine_for(conn).server_version(conn_info(conn, "catalog"))
    except Exception:  # noqa: BLE001 - sin conexión no se restringe por versión; se reintenta después
        return None
    if version:
        # Sesión aparte: no debe confirmar la transacción en curso de quien llama
        from app.core.db import SessionLocal

        with SessionLocal() as other:
            row = other.get(DbConnection, conn.id)
            if row is not None:
                row.server_version = version
                other.commit()
        conn.server_version = version
    return version
