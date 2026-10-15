from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    # Metadata de DBA Manager. Por defecto Oracle (usuario DBAMGR); METADATA_URL permite
    # apuntar a cualquier URL de SQLAlchemy (p. ej. para migrar la metadata a otro motor).
    metadata_url: str | None = None
    metadata_user: str = "DBAMGR"
    dbamgr_password: str = ""
    metadata_dsn: str = "localhost:1521/FREEPDB1"

    # ---- Keycloak / OIDC (todo configurable: puede ser el Keycloak incluido o uno corporativo) ----
    # URL con la que el NAVEGADOR llega a Keycloak; con ella se arma el issuer de los tokens.
    keycloak_public_url: str = "http://localhost:8180"
    # URL con la que el BACKEND llega a Keycloak (red interna); por defecto la pública.
    keycloak_internal_url: str | None = None
    keycloak_realm: str = "dbamanager"
    # Cliente público (navegador) con el que inician sesión los usuarios.
    keycloak_client_id: str = "dbamanager-web"
    # Overrides para proveedores OIDC con otra estructura de URLs.
    oidc_issuer_url: str | None = None  # env OIDC_ISSUER_URL
    oidc_jwks_uri: str | None = None  # env OIDC_JWKS_URI
    oidc_audience: str | None = None  # por defecto keycloak_client_id; "" = no validar audiencia
    # De dónde salen los roles del token: "realm" (realm_access.roles) o "client" (resource_access.<cliente>.roles)
    keycloak_roles_source: str = "realm"
    keycloak_roles_client: str | None = None  # cliente de los roles si source=client (por defecto keycloak_client_id)
    # Nombres de los roles en Keycloak (por si el realm corporativo usa otros nombres)
    role_requester: str = "solicitante"
    role_dba: str = "dba"
    role_dba_admin: str = "dba_admin"
    role_admin: str = "admin"
    # Cliente confidencial con cuenta de servicio para administrar roles desde DBA Manager (opcional).
    # Necesita en realm-management: view-users, query-users, manage-users (y view-clients si source=client).
    keycloak_admin_client_id: str | None = None
    keycloak_admin_client_secret: str | None = None

    secrets_key: str
    cors_origins: str = ""

    @property
    def sqlalchemy_url(self) -> str:
        if self.metadata_url:
            return self.metadata_url
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
        return bool(self.keycloak_admin_client_id and self.keycloak_admin_client_secret)


@lru_cache
def get_settings() -> Settings:
    return Settings()
