"""Administración de roles en Keycloak desde DBA Manager (API REST de administración del realm).

Dos modos (KEYCLOAK_ROLES_ADMIN_MODE):
  * token: se usa el token del admin de DBA Manager que inició sesión. No requiere usuario master,
    cuenta de servicio ni secreto: basta con que ese usuario tenga en su realm, cliente
    `realm-management`, los roles view-users, query-users y manage-users (y view-clients si los
    roles de la app son de cliente). Cada cambio en Keycloak queda hecho con su propia identidad.
  * service-account: cliente confidencial con cuenta de servicio y esos mismos roles.
No requiere view-realm ni manage-realm. DBA Manager solo toca los 4 roles que conoce.
"""
import time
from contextvars import ContextVar

import httpx
from fastapi import HTTPException

from app.core.config import get_settings

_token: dict = {"value": None, "exp": 0.0}
_client_uuid: dict[str, str] = {}
_caller_token: ContextVar[str | None] = ContextVar("caller_token", default=None)
_PERMS = "view-users, query-users y manage-users del cliente realm-management"


def use_caller_token(token: str | None) -> None:
    """Modo token: las llamadas de esta petición usan el token del admin que inició sesión."""
    _caller_token.set(token)


def _cfg():
    s = get_settings()
    if not s.role_management_enabled:
        raise HTTPException(409, "La administración de roles desde DBA Manager está deshabilitada "
                                 "(KEYCLOAK_ROLES_ADMIN_MODE=off o falta el cliente de servicio)")
    return s


def _no_perms_msg() -> str:
    if get_settings().keycloak_roles_admin_mode == "token":
        return f"Tu usuario de Keycloak no tiene permisos para administrar usuarios del realm: pide que te asignen {_PERMS}"
    return f"La cuenta de servicio no tiene permisos suficientes: asígnale {_PERMS}"


def _admin_base() -> str:
    s = get_settings()
    return f"{s.keycloak_backend_url}/admin/realms/{s.keycloak_realm}"


def _get_token() -> str:
    s = _cfg()
    if s.keycloak_roles_admin_mode == "token":
        token = _caller_token.get()
        if not token:
            raise HTTPException(401, "Falta el token del usuario para administrar roles")
        return token
    if _token["value"] and _token["exp"] - time.time() > 30:
        return _token["value"]
    try:
        r = httpx.post(
            f"{s.keycloak_backend_url}/realms/{s.keycloak_realm}/protocol/openid-connect/token",
            data={"grant_type": "client_credentials", "client_id": s.keycloak_admin_client_id,
                  "client_secret": s.keycloak_admin_client_secret},
            timeout=10,
        )
    except httpx.HTTPError as exc:
        raise HTTPException(502, f"No se pudo contactar a Keycloak: {exc}") from exc
    if r.status_code != 200:
        raise HTTPException(502, f"Keycloak rechazó la cuenta de servicio ({r.status_code}): revisa client id/secret")
    data = r.json()
    _token.update(value=data["access_token"], exp=time.time() + data.get("expires_in", 60))
    return _token["value"]


def _call(method: str, path: str, **kw) -> httpx.Response:
    try:
        r = httpx.request(method, f"{_admin_base()}{path}", headers={"Authorization": f"Bearer {_get_token()}"},
                          timeout=15, **kw)
    except httpx.HTTPError as exc:
        raise HTTPException(502, f"No se pudo contactar a Keycloak: {exc}") from exc
    if r.status_code in (401, 403):
        raise HTTPException(403, _no_perms_msg())
    if r.status_code == 404:
        raise HTTPException(404, "No encontrado en Keycloak")
    if r.status_code >= 400:
        raise HTTPException(502, f"Error de Keycloak {r.status_code}: {r.text[:200]}")
    return r


def _roles_client_uuid() -> str:
    """UUID interno del cliente cuando los roles son de cliente."""
    cid = get_settings().roles_client
    if cid not in _client_uuid:
        found = _call("GET", "/clients", params={"clientId": cid}).json()
        if not found:
            raise HTTPException(409, f"No existe el cliente {cid} en Keycloak")
        _client_uuid[cid] = found[0]["id"]
    return _client_uuid[cid]


def _mapping_path(user_id: str) -> str:
    if get_settings().keycloak_roles_source == "client":
        return f"/users/{user_id}/role-mappings/clients/{_roles_client_uuid()}"
    return f"/users/{user_id}/role-mappings/realm"


def _role_reprs(user_id: str) -> dict[str, dict]:
    """Representación (id, nombre) de los roles asignables o asignados al usuario.

    Se usa role-mappings/available en lugar de /roles/{nombre} para no requerir view-realm:
    basta con los permisos sobre usuarios (view-users / manage-users)."""
    reprs = {}
    for path in (_mapping_path(user_id), f"{_mapping_path(user_id)}/available"):
        for r in _call("GET", path).json():
            reprs[r["name"]] = {"id": r["id"], "name": r["name"]}
    return reprs


def _to_internal(external: set[str]) -> list[str]:
    return sorted(i for i, e in get_settings().role_names.items() if e in external)


def user_roles(user_id: str) -> list[str]:
    # /composite incluye roles heredados por grupos o roles compuestos
    effective = {r["name"] for r in _call("GET", f"{_mapping_path(user_id)}/composite").json()}
    return _to_internal(effective)


def direct_roles(user_id: str) -> list[str]:
    return _to_internal({r["name"] for r in _call("GET", _mapping_path(user_id)).json()})


def list_users(search: str | None, first: int, limit: int) -> list[dict]:
    params = {"first": first, "max": limit, "briefRepresentation": "true"}
    if search:
        params["search"] = search
    users = _call("GET", "/users", params=params).json()
    out = []
    for u in users:
        if u.get("username", "").startswith("service-account-"):
            continue
        out.append({
            "id": u["id"], "username": u["username"], "email": u.get("email"),
            "full_name": " ".join(p for p in (u.get("firstName"), u.get("lastName")) if p),
            "enabled": u.get("enabled", True),
            "roles": user_roles(u["id"]), "direct_roles": direct_roles(u["id"]),
        })
    return out


def get_user(user_id: str) -> dict:
    u = _call("GET", f"/users/{user_id}").json()
    return {"id": u["id"], "username": u["username"], "roles": user_roles(user_id), "direct_roles": direct_roles(user_id)}


def set_roles(user_id: str, wanted: set[str]) -> tuple[list[str], list[str]]:
    """Ajusta los roles directos del usuario a `wanted` (solo los 4 de la app). Devuelve (agregados, quitados)."""
    names = get_settings().role_names
    current = set(direct_roles(user_id))
    add, remove = sorted(wanted - current), sorted(current - wanted)
    reprs = _role_reprs(user_id) if add or remove else {}
    missing = [f"{r} ({names[r]})" for r in add + remove if names[r] not in reprs]
    if missing:
        raise HTTPException(409, "Estos roles no existen en Keycloak: " + ", ".join(missing))
    if add:
        _call("POST", _mapping_path(user_id), json=[reprs[names[r]] for r in add])
    if remove:
        _call("DELETE", _mapping_path(user_id), json=[reprs[names[r]] for r in remove])
    return add, remove


def status() -> dict:
    """Diagnóstico para la pantalla de administración."""
    s = get_settings()
    info = {
        "issuer": s.oidc_issuer, "realm": s.keycloak_realm, "client_id": s.keycloak_client_id,
        "public_url": s.keycloak_public_url, "internal_url": s.keycloak_backend_url,
        "roles_source": s.keycloak_roles_source,
        "roles_client": s.roles_client if s.keycloak_roles_source == "client" else None,
        "role_names": s.role_names, "management_enabled": s.role_management_enabled,
        "management_mode": s.keycloak_roles_admin_mode,
        "management_ok": False, "management_error": None, "missing_roles": [],
    }
    if not s.role_management_enabled:
        return info
    try:
        _get_token()
        users = _call("GET", "/users", params={"max": 1, "briefRepresentation": "true"}).json()
        missing = []
        if users:
            reprs = _role_reprs(users[0]["id"])
            missing = [f"{i} ({e})" for i, e in s.role_names.items() if e not in reprs]
        info.update(management_ok=True, missing_roles=missing)
    except HTTPException as exc:
        info["management_error"] = exc.detail
    return info
