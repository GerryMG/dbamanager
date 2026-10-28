"""Configuración: variables de entorno o archivo .env (en la raíz del proyecto o en backend/).

Todas las variables están documentadas en .env.example.
"""
from functools import lru_cache
from pathlib import Path

from pydantic_settings import BaseSettings, SettingsConfigDict

ROOT = Path(__file__).resolve().parents[3]  # raíz del proyecto (contiene backend/ y frontend/)


class Settings(BaseSettings):
    # Las variables de entorno tienen prioridad sobre el archivo .env
    model_config = SettingsConfigDict(env_file=(ROOT / ".env", ".env"), extra="ignore")

    # ---- Aplicación ----
    port: int = 8080
    # Carpeta con el frontend compilado (npm run build). Vacío = <raíz>/frontend/dist
    static_dir: str | None = None
    cors_origins: str = ""
    log_level: str = "info"

    # ---- Metadata de DBA Manager (Oracle externo) ----
    metadata_url: str | None = None
    metadata_dsn: str | None = None
    metadata_user: str = "DBAMGR"
    dbamgr_password: str = ""

    # ---- Seguridad ----
    secrets_key: str

    # ---- Keycloak / OIDC ----
    keycloak_public_url: str = "http://localhost:8180"
    keycloak_internal_url: str | None = None
    keycloak_realm: str = "dbamanager"
    keycloak_client_id: str = "dbamanager-web"
    oidc_issuer_url: str | None = None
    oidc_jwks_uri: str | None = None
    oidc_audience: str | None = None
    keycloak_roles_source: str = "realm"
    keycloak_roles_client: str | None = None
    role_requester: str = "solicitante"
    role_dba: str = "dba"
    role_dba_admin: str = "dba_admin"
    role_admin: str = "admin"
    # Asignar roles desde DBA Manager: "token" (con el usuario de Keycloak del admin que inició sesión),
    # "service-account" (cliente confidencial) u "off".
    keycloak_roles_admin_mode: str = "token"
    keycloak_admin_client_id: str | None = None
    keycloak_admin_client_secret: str | None = None

    # ---- Demo ----
    seed_demo: bool = False
    seed_oracle_host: str = "localhost"
    seed_oracle_port: int = 1521
    catalog_password: str | None = None
    executor_password: str | None = None

    @property
    def static_path(self) -> Path:
        if self.static_dir:
            return Path(self.static_dir)
        # local: frontend/dist ; imagen: /app/static
        for candidate in (ROOT / "frontend" / "dist", ROOT / "static"):
            if (candidate / "index.html").exists():
                return candidate
        return ROOT / "frontend" / "dist"

    @property
    def sqlalchemy_url(self) -> str:
        if self.metadata_url:
            return self.metadata_url
        if not self.metadata_dsn:
            raise RuntimeError("Falta METADATA_DSN (host:puerto/servicio de la metadata) o METADATA_URL")
        host_port, service = self.metadata_dsn.split("/", 1)
        return f"oracle+oracledb://{self.metadata_user}:{self.dbamgr_password}@{host_port}/?service_name={service}"

    @property
    def keycloak_backend_url(self) -> str:
        return (self.keycloak_internal_url or self.keycloak_public_url).rstrip("/")

    @property
    def oidc_issuer(self) -> str:
        return self.oidc_issuer_url or f"{self.keycloak_public_url.rstrip('/')}/realms/{self.keycloak_realm}"

    @property
    def oidc_jwks_url(self) -> str:
        return self.oidc_jwks_uri or \
            f"{self.keycloak_backend_url}/realms/{self.keycloak_realm}/protocol/openid-connect/certs"

    @property
    def audience(self) -> str | None:
        if self.oidc_audience is None:
            return self.keycloak_client_id
        return self.oidc_audience or None

    @property
    def roles_client(self) -> str:
        return self.keycloak_roles_client or self.keycloak_client_id

    @property
    def role_names(self) -> dict[str, str]:
        """Rol interno de DBA Manager -> nombre del rol en Keycloak."""
        return {"solicitante": self.role_requester, "dba": self.role_dba,
                "dba_admin": self.role_dba_admin, "admin": self.role_admin}

    @property
    def role_management_enabled(self) -> bool:
        if self.keycloak_roles_admin_mode == "token":
            return True
        if self.keycloak_roles_admin_mode == "service-account":
            return bool(self.keycloak_admin_client_id and self.keycloak_admin_client_secret)
        return False


@lru_cache
def get_settings() -> Settings:
    return Settings()
