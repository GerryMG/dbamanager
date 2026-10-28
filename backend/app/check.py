"""Verificación de arranque: configuración y servidores externos (metadata y Keycloak).

Se ejecuta antes de las migraciones. Errores fatales (sin metadata o sin llave de cifrado)
detienen el arranque con un mensaje claro; problemas de identidad se reportan como advertencia.
"""
import sys
import time

import httpx
from sqlalchemy import create_engine, text


def fail(msg: str) -> None:
    print(f"[ERROR] {msg}", flush=True)
    sys.exit(1)


def warn(msg: str) -> None:
    print(f"[AVISO] {msg}", flush=True)


def ok(msg: str) -> None:
    print(f"[OK] {msg}", flush=True)


def main() -> None:
    from pydantic import ValidationError

    try:
        from app.core.config import get_settings

        s = get_settings()
    except ValidationError as exc:
        missing = ", ".join(str(e["loc"][0]).upper() for e in exc.errors())
        fail(f"Faltan variables obligatorias en .env: {missing}")
        return

    # ---------------------------------------------------------------- metadata
    if not s.metadata_url and not (s.metadata_dsn and s.dbamgr_password):
        fail("Configura la base de metadata: METADATA_DSN=host:puerto/servicio y DBAMGR_PASSWORD "
             "(o METADATA_URL). Qué debe existir: docs/ORACLE.md")
    target = s.metadata_url.split("@")[-1] if s.metadata_url else f"{s.metadata_user}@{s.metadata_dsn}"
    connect_args = {"tcp_connect_timeout": 5} if s.sqlalchemy_url.startswith("oracle") else {}
    engine = create_engine(s.sqlalchemy_url, pool_pre_ping=True, connect_args=connect_args)
    last = None
    for attempt in range(1, 7):
        try:
            with engine.connect() as conn:
                conn.execute(text("SELECT 1 FROM dual" if engine.dialect.name == "oracle" else "SELECT 1"))
            ok(f"Metadata: conectado a {target}")
            break
        except Exception as exc:  # noqa: BLE001 - se reporta el error del driver
            last = exc
            print(f"   intento {attempt}/6: sin conexión a la metadata ({target})"
                  + ("; reintentando…" if attempt < 6 else ""), flush=True)
            if attempt < 6:
                time.sleep(3)
    else:
        fail(f"No se pudo conectar a la metadata {target}: {str(last).splitlines()[0]}")
    engine.dispose()

    # ---------------------------------------------------------------- identidad (OIDC / Keycloak)
    discovery = (f"{s.keycloak_backend_url}/realms/{s.keycloak_realm}/.well-known/openid-configuration"
                 if not s.oidc_issuer_url else f"{s.oidc_issuer_url.rstrip('/')}/.well-known/openid-configuration")
    try:
        r = httpx.get(discovery, timeout=10)
        r.raise_for_status()
        issuer = r.json().get("issuer")
        if issuer != s.oidc_issuer:
            warn(f"El issuer de Keycloak es '{issuer}' pero DBA Manager espera '{s.oidc_issuer}': los inicios de sesión "
                 "fallarán. Revisa KEYCLOAK_PUBLIC_URL (debe ser la URL que ve el navegador) o define OIDC_ISSUER_URL.")
        else:
            ok(f"Identidad: issuer {issuer}")
        httpx.get(s.oidc_jwks_url, timeout=10).raise_for_status()
        ok("Identidad: llaves de firma (JWKS) accesibles")
    except Exception as exc:  # noqa: BLE001
        warn(f"No se pudo consultar el proveedor de identidad ({discovery}): {exc}. "
             "Revisa KEYCLOAK_INTERNAL_URL / KEYCLOAK_PUBLIC_URL; los inicios de sesión fallarán hasta corregirlo.")

    if s.keycloak_roles_admin_mode == "service-account" and s.role_management_enabled:
        try:
            r = httpx.post(f"{s.keycloak_backend_url}/realms/{s.keycloak_realm}/protocol/openid-connect/token",
                           data={"grant_type": "client_credentials", "client_id": s.keycloak_admin_client_id,
                                 "client_secret": s.keycloak_admin_client_secret}, timeout=10)
            if r.status_code == 200:
                ok("Administración de roles: cuenta de servicio válida")
            else:
                warn(f"Administración de roles: Keycloak rechazó {s.keycloak_admin_client_id} ({r.status_code}). "
                     "Ejecuta scripts/keycloak/configurar_cliente_admin.sh o revisa el secreto.")
        except Exception as exc:  # noqa: BLE001
            warn(f"Administración de roles: sin respuesta de Keycloak ({exc})")
    elif s.keycloak_roles_admin_mode == "token":
        print("[INFO] Administración de roles: con el usuario de Keycloak de cada admin (modo token)", flush=True)
    else:
        print("[INFO] Administración de roles desde la app deshabilitada", flush=True)
    if not s.static_path.joinpath("index.html").exists():
        warn(f"No se encontró el frontend compilado en {s.static_path} (npm run build); solo funcionará la API")


if __name__ == "__main__":
    main()
