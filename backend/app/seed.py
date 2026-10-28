"""Carga datos demo (conexiones DEV/PROD al Oracle de demo, proyectos y alcances) si la metadata está vacía.

Se activa con SEED_DEMO=true. No hace nada si ya existen conexiones.
"""
from sqlalchemy import func, select

from app.core.config import get_settings
from app.core.crypto import encrypt
from app.core.db import SessionLocal
from app.models import ApproverScope, DbConnection, Project
from app.services.audit import audit


def main() -> None:
    settings = get_settings()
    if not settings.seed_demo:
        return
    if not (settings.catalog_password and settings.executor_password):
        print("SEED_DEMO=true requiere CATALOG_PASSWORD y EXECUTOR_PASSWORD; no se cargó la demo")
        return
    with SessionLocal() as db:
        if db.scalar(select(func.count()).select_from(DbConnection)):
            return
        host, port = settings.seed_oracle_host, settings.seed_oracle_port
        conns = {}
        for name, env, service, desc in [
            ("Oracle DEV", "DEV", "DEVPDB", "Base de desarrollo (PDB local DEVPDB)"),
            ("Oracle PROD", "PROD", "PRODPDB", "Base productiva simulada (PDB local PRODPDB)"),
        ]:
            c = DbConnection(
                name=name, engine="oracle", environment=env, description=desc, host=host, port=port,
                service_name=service, catalog_user="DBAMGR_CATALOG",
                catalog_password_enc=encrypt(settings.catalog_password),
                exec_user="DBAMGR_EXEC", exec_password_enc=encrypt(settings.executor_password),
                allow_execution=True, required_approvals=1, created_by="seed",
            )
            db.add(c)
            conns[env] = c
        db.add_all([
            Project(code="BI-VENTAS", name="Tablero de ventas para BI", owner="pedro.bi",
                    description="Exposición de información de ventas al equipo de BI"),
            Project(code="CRM-2026", name="Migración CRM 2026", owner="ana.dev",
                    description="Nuevo módulo de clientes"),
        ])
        db.flush()
        for env in ("DEV", "PROD"):
            for schema in ("VENTAS", "BI_LECTOR"):
                db.add(ApproverScope(username="luis.dba", connection_id=conns[env].id, schema_name=schema,
                                     granted_by="seed"))
        audit(db, "seed", "DEMO_SEEDED", "metadata", None)
        db.commit()
        print("Datos demo cargados")


if __name__ == "__main__":
    main()
