"""Autenticación OIDC (Keycloak por defecto, configurable por variables de entorno).

Cualquier proveedor OIDC que publique JWKS funciona; LDAP/AD se integra vía User Federation
de Keycloak sin cambios aquí. Los roles se leen de `realm_access.roles` o de
`resource_access.<cliente>.roles` (KEYCLOAK_ROLES_SOURCE) y sus nombres en Keycloak se traducen
a los roles internos (ROLE_REQUESTER, ROLE_DBA, ROLE_DBA_ADMIN, ROLE_ADMIN).
"""
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone

import jwt
from fastapi import Depends, HTTPException, Request, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from sqlalchemy.orm import Session

from app.core.config import get_settings
from app.core.db import get_db

ROLE_REQUESTER = "solicitante"
ROLE_DBA = "dba"
ROLE_DBA_ADMIN = "dba_admin"
ROLE_ADMIN = "admin"
APP_ROLES = {ROLE_REQUESTER, ROLE_DBA, ROLE_DBA_ADMIN, ROLE_ADMIN}

_bearer = HTTPBearer(auto_error=False)
_jwks_client = jwt.PyJWKClient(get_settings().oidc_jwks_url, cache_keys=True, lifespan=600)
_last_seen: dict[str, datetime] = {}


@dataclass
class CurrentUser:
    username: str
    sub: str
    full_name: str = ""
    email: str = ""
    roles: set[str] = field(default_factory=set)
    ip: str | None = None
    access_token: str | None = field(default=None, repr=False)  # para administrar roles en modo token

    @property
    def is_admin(self) -> bool:
        return ROLE_ADMIN in self.roles

    @property
    def is_dba_admin(self) -> bool:
        return ROLE_DBA_ADMIN in self.roles

    @property
    def is_dba(self) -> bool:
        return self.is_dba_admin or ROLE_DBA in self.roles


def _decode(token: str) -> dict:
    settings = get_settings()
    try:
        key = _jwks_client.get_signing_key_from_jwt(token).key
        return jwt.decode(
            token,
            key,
            algorithms=["RS256", "RS384", "RS512", "ES256", "ES384", "PS256"],
            issuer=settings.oidc_issuer,
            audience=settings.audience,
            options={"require": ["exp", "iat", "sub"], "verify_aud": settings.audience is not None},
        )
    except jwt.PyJWTError as exc:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, f"Token inválido: {exc}") from exc


def app_roles_from_claims(claims: dict) -> set[str]:
    """Roles del token -> roles internos de DBA Manager, según la configuración."""
    settings = get_settings()
    if settings.keycloak_roles_source == "client":
        token_roles = set(claims.get("resource_access", {}).get(settings.roles_client, {}).get("roles", []))
    else:
        token_roles = set(claims.get("realm_access", {}).get("roles", []))
    return {internal for internal, external in settings.role_names.items() if external in token_roles}


def _touch_user(db: Session, user: CurrentUser) -> None:
    """Mantiene el directorio local de usuarios (para mostrar nombres y asignar alcances)."""
    from app.models import AppUser

    now = datetime.now(timezone.utc)
    if _last_seen.get(user.username, datetime.min.replace(tzinfo=timezone.utc)) > now - timedelta(minutes=5):
        return
    row = db.get(AppUser, user.username) or AppUser(username=user.username)
    row.sub = user.sub
    row.full_name = user.full_name
    row.email = user.email
    row.roles = ",".join(sorted(user.roles))
    row.last_seen = now
    db.merge(row)
    db.commit()
    _last_seen[user.username] = now


def get_current_user(
    request: Request,
    creds: HTTPAuthorizationCredentials | None = Depends(_bearer),
    db: Session = Depends(get_db),
) -> CurrentUser:
    if creds is None:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Falta token de acceso")
    claims = _decode(creds.credentials)
    roles = app_roles_from_claims(claims)
    user = CurrentUser(
        username=claims.get("preferred_username") or claims["sub"],
        sub=claims["sub"],
        full_name=claims.get("name", ""),
        email=claims.get("email", ""),
        roles=roles,
        ip=request.client.host if request.client else None,
        access_token=creds.credentials,
    )
    _touch_user(db, user)
    return user


def require_admin(user: CurrentUser = Depends(get_current_user)) -> CurrentUser:
    if not user.is_admin:
        raise HTTPException(status.HTTP_403_FORBIDDEN, "Requiere rol admin")
    return user


def require_dba(user: CurrentUser = Depends(get_current_user)) -> CurrentUser:
    if not user.is_dba:
        raise HTTPException(status.HTTP_403_FORBIDDEN, "Requiere rol dba o dba_admin")
    return user
