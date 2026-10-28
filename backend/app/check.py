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

    # ---------------------------------------------------------------- secretos y configuración insegura
    placeholders = {"change_me", "changeme", "cambiar", "password", "secret"}
    try:
        from cryptography.fernet import Fernet

        Fernet(s.secrets_key.encode())
    except Exception:  # noqa: BLE001
        fail("SECRETS_KEY no es una llave válida (32 bytes en base64 url-safe). Genera una con: "
             "python -c \"import base64,os;print(base64.urlsafe_b64encode(os.urandom(32)).decode())\"")
    for name, value in (("DBAMGR_PASSWORD", s.dbamgr_password), ("KEYCLOAK_ADMIN_CLIENT_SECRET", s.keycloak_admin_client_secret)):
        if value and value.strip().lower() in placeholders:
            fail(f"{name} tiene un valor de ejemplo ('{value}'): define uno real")
    pub = s.keycloak_public_url.lower()
    if pub.startswith("http://") and not any(h in pub for h in ("://localhost", "://127.0.0.1")):
        warn("KEYCLOAK_PUBLIC_URL usa http sin cifrar: en producción usa https para no exponer credenciales y tokens")
    if s.audience is None:
        warn("OIDC_AUDIENCE vacío: no se valida que los tokens sean para DBA Manager (configura el audience mapper)")
    if s.api_docs_enabled:
        warn("API_DOCS_ENABLED=true: la documentación de la API es pública (/api/docs); desactívala en producción")
    if s.forwarded_allow_ips.strip() == "*":
        warn("FORWARDED_ALLOW_IPS=*: la IP de la bitácora se toma de X-Forwarded-For; úsalo solo detrás de tu proxy")
    if s.self_execution_hours == 0:
        warn("SELF_EXECUTION_HOURS=0: la ejecución desde la app, una vez activada, no vence")
    if s.metadata_restore_replace:
        warn("METADATA_RESTORE_REPLACE=true: un admin puede reemplazar toda la metadata (incluida la bitácora)")

    # ---------------------------------------------------------------- metadata
    if not s.metadata_url and not (s.metadata_dsn and s.dbamgr_password):
        fail("Configura la base de metadata: METADATA_DSN=host:puerto/servicio y DBAMGR_PASSWORD "
             "(o METADATA_URL). Qué debe existir: docs/ORACLE.md")
    target = s.metadata_target
    connect_args = dict(s.metadata_connect_args)
    if s.sqlalchemy_url.startswith("oracle"):
        connect_args["tcp_connect_timeout"] = 5
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
                     "Revisa el client id y el secreto (docs/KEYCLOAK.md).")
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
