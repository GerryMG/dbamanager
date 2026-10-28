import json
from datetime import datetime, timezone

from fastapi import APIRouter, Depends, File, HTTPException, Query, UploadFile
from fastapi.responses import JSONResponse
from sqlalchemy import select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from app.core.config import get_settings
from app.core.crypto import encrypt
from app.core.db import get_db
from app.core.security import CurrentUser, require_admin, require_dba
from app.engines import get_engine
from app.models import AppUser, ApproverScope, AuditLog, DbConnection, Project
from app.schemas import AuditOut, ConnectionIn, ConnectionOut, ProjectIn, RolesIn, ScopeIn
from app.services import backup, keycloak_admin
from app.services.audit import audit
from app.services.connections import conn_info, load_connection

router = APIRouter(prefix="/api/admin", tags=["admin"])


# ------------------------------------------------------------------ conexiones
@router.get("/connections", response_model=list[ConnectionOut])
def list_connections(db: Session = Depends(get_db), user: CurrentUser = Depends(require_admin)):
    return [ConnectionOut.of(c) for c in db.scalars(select(DbConnection).order_by(DbConnection.name))]


def _apply_connection(c: DbConnection, data: ConnectionIn) -> None:
    get_engine(data.engine)  # valida motor soportado
    if (c.host, c.port, c.service_name) != (data.host, data.port, data.service_name):
        c.server_version = None  # otro servidor: se vuelve a detectar
    for f in ("name", "engine", "environment", "description", "host", "port", "service_name",
              "catalog_user", "exec_user", "allow_execution", "use_tls", "required_approvals", "active"):
        setattr(c, f, getattr(data, f))
    c.environment = c.environment.upper()
    if data.catalog_password:
        c.catalog_password_enc = encrypt(data.catalog_password)
    if data.exec_password:
        c.exec_password_enc = encrypt(data.exec_password)
    if not data.exec_user:
        c.exec_user, c.exec_password_enc, c.allow_execution = None, None, False


@router.post("/connections", response_model=ConnectionOut)
def create_connection(data: ConnectionIn, db: Session = Depends(get_db), user: CurrentUser = Depends(require_admin)):
    if not data.catalog_password:
        raise HTTPException(422, "La contraseña de la cuenta de catálogo es obligatoria")
    c = DbConnection(created_by=user.username)
    _apply_connection(c, data)
    db.add(c)
    try:
        db.flush()
    except IntegrityError as exc:
        raise HTTPException(409, "Ya existe una conexión con ese nombre") from exc
    audit(db, user, "CONNECTION_CREATED", "connection", c.id, **data.model_dump(exclude={"catalog_password", "exec_password"}))
    db.commit()
    return ConnectionOut.of(c)


@router.put("/connections/{connection_id}", response_model=ConnectionOut)
def update_connection(connection_id: int, data: ConnectionIn, db: Session = Depends(get_db),
                      user: CurrentUser = Depends(require_admin)):
    c = load_connection(db, connection_id, active_only=False)
    _apply_connection(c, data)
    audit(db, user, "CONNECTION_UPDATED", "connection", c.id,
          **data.model_dump(exclude={"catalog_password", "exec_password"}),
          catalog_password_changed=bool(data.catalog_password), exec_password_changed=bool(data.exec_password))
    db.commit()
    return ConnectionOut.of(c)


@router.post("/connections/{connection_id}/test")
def test_connection(connection_id: int, role: str = Query("catalog", pattern="^(catalog|exec)$"),
                    db: Session = Depends(get_db), user: CurrentUser = Depends(require_admin)):
    c = load_connection(db, connection_id, active_only=False)
    info = conn_info(c, role, user.username)
    try:
        result = get_engine(c.engine).test_connection(info)
        if result.get("version") and result["version"] != c.server_version:
            c.server_version = result["version"]
            db.commit()
        return {"ok": True, **result}
    except Exception as exc:  # noqa: BLE001 - se reporta el error del driver al admin
        return {"ok": False, "error": str(exc)}


# ------------------------------------------------------------------ proyectos
@router.post("/projects")
def create_project(data: ProjectIn, db: Session = Depends(get_db), user: CurrentUser = Depends(require_dba)):
    p = Project(**data.model_dump())
    p.code = p.code.upper()
    db.add(p)
    try:
        db.flush()
    except IntegrityError as exc:
        raise HTTPException(409, "Ya existe un proyecto con ese código") from exc
    audit(db, user, "PROJECT_CREATED", "project", p.id, **data.model_dump())
    db.commit()
    return {"id": p.id}


@router.put("/projects/{project_id}")
def update_project(project_id: int, data: ProjectIn, db: Session = Depends(get_db),
                   user: CurrentUser = Depends(require_dba)):
    p = db.get(Project, project_id)
    if p is None:
        raise HTTPException(404, "Proyecto no encontrado")
    for k, v in data.model_dump().items():
        setattr(p, k, v)
    p.code = p.code.upper()
    audit(db, user, "PROJECT_UPDATED", "project", p.id, **data.model_dump())
    db.commit()
    return {"id": p.id}


# ------------------------------------------------------------------ alcances de DBAs
@router.get("/scopes")
def list_scopes(db: Session = Depends(get_db), user: CurrentUser = Depends(require_dba)):
    rows = db.scalars(select(ApproverScope).order_by(ApproverScope.username, ApproverScope.connection_id)).all()
    return [{"id": r.id, "username": r.username, "connection_id": r.connection_id, "schema_name": r.schema_name,
             "granted_by": r.granted_by, "granted_at": r.granted_at} for r in rows]


@router.post("/scopes")
def add_scope(data: ScopeIn, db: Session = Depends(get_db), user: CurrentUser = Depends(require_admin)):
    load_connection(db, data.connection_id, active_only=False)
    if data.username == user.username:
        raise HTTPException(403, "No puedes asignarte alcances de aprobación a ti mismo; pídeselo a otro administrador")
    s = ApproverScope(username=data.username, connection_id=data.connection_id,
                      schema_name=data.schema_name.strip().upper(), granted_by=user.username)
    db.add(s)
    try:
        db.flush()
    except IntegrityError as exc:
        raise HTTPException(409, "Ese alcance ya existe") from exc
    audit(db, user, "SCOPE_GRANTED", "scope", s.id, **data.model_dump())
    db.commit()
    return {"id": s.id}


@router.delete("/scopes/{scope_id}")
def delete_scope(scope_id: int, db: Session = Depends(get_db), user: CurrentUser = Depends(require_admin)):
    s = db.get(ApproverScope, scope_id)
    if s is None:
        raise HTTPException(404, "Alcance no encontrado")
    audit(db, user, "SCOPE_REVOKED", "scope", s.id, username=s.username, connection_id=s.connection_id,
          schema_name=s.schema_name)
    db.delete(s)
    db.commit()
    return {"ok": True}


@router.get("/users")
def list_users(db: Session = Depends(get_db), user: CurrentUser = Depends(require_dba)):
    rows = db.scalars(select(AppUser).order_by(AppUser.username)).all()
    return [{"username": u.username, "full_name": u.full_name, "email": u.email,
             "roles": [r for r in (u.roles or "").split(",") if r], "last_seen": u.last_seen} for u in rows]


# ------------------------------------------------------------------ usuarios y roles (Keycloak)
@router.get("/identity")
def identity_status(user: CurrentUser = Depends(require_admin)):
    """Configuración del proveedor de identidad y si la administración de roles está operativa."""
    keycloak_admin.use_caller_token(user.access_token)
    return keycloak_admin.status()


@router.get("/idp-users")
def idp_users(search: str | None = Query(None, max_length=100), first: int = Query(0, ge=0),
              limit: int = Query(50, ge=1, le=200), db: Session = Depends(get_db),
              user: CurrentUser = Depends(require_admin)):
    keycloak_admin.use_caller_token(user.access_token)
    users = keycloak_admin.list_users(search, first, limit)
    scopes: dict[str, int] = {}
    for sc in db.scalars(select(ApproverScope)):
        scopes[sc.username] = scopes.get(sc.username, 0) + 1
    seen = {u.username: u.last_seen for u in db.scalars(select(AppUser))}
    for u in users:
        u["scopes"] = scopes.get(u["username"], 0)
        u["last_seen"] = seen.get(u["username"])
    return users


@router.put("/idp-users/{user_id}/roles")
def set_user_roles(user_id: str, data: RolesIn, db: Session = Depends(get_db),
                   user: CurrentUser = Depends(require_admin)):
    keycloak_admin.use_caller_token(user.access_token)
    valid = set(get_settings().role_names)
    wanted = set(data.roles)
    if wanted - valid:
        raise HTTPException(422, f"Roles desconocidos: {', '.join(sorted(wanted - valid))}")
    target = keycloak_admin.get_user(user_id)
    if target["username"] == user.username:
        raise HTTPException(403, "No puedes cambiar tus propios roles; pídeselo a otro administrador")
    added, removed = keycloak_admin.set_roles(user_id, wanted)
    effective = keycloak_admin.user_roles(user_id)
    row = db.get(AppUser, target["username"])
    if row is not None:
        row.roles = ",".join(effective)
    if added or removed:
        audit(db, user, "ROLES_CHANGED", "user", target["username"], added=added, removed=removed)
    db.commit()
    note = None
    if "dba" in effective and "dba_admin" not in effective and not db.scalar(
            select(ApproverScope.id).where(ApproverScope.username == target["username"])):
        note = "Tiene rol dba pero ningún alcance de esquemas: asígnale alcances para que pueda aprobar"
    return {"username": target["username"], "roles": effective, "added": added, "removed": removed, "note": note}


# ------------------------------------------------------------------ bitácora
@router.get("/audit", response_model=list[AuditOut])
def audit_log(username: str | None = None, action: str | None = None, entity_type: str | None = None,
              limit: int = Query(300, le=2000), db: Session = Depends(get_db),
              user: CurrentUser = Depends(require_dba)):
    stmt = select(AuditLog).order_by(AuditLog.id.desc())
    if username:
        stmt = stmt.where(AuditLog.username == username)
    if action:
        stmt = stmt.where(AuditLog.action == action)
    if entity_type:
        stmt = stmt.where(AuditLog.entity_type == entity_type)
    return [AuditOut(id=r.id, at=r.at, username=r.username, action=r.action, entity_type=r.entity_type,
                     entity_id=r.entity_id, details=json.loads(r.details) if r.details else None, ip=r.ip)
            for r in db.scalars(stmt.limit(limit))]


# ------------------------------------------------------------------ respaldo / migración de metadata
@router.get("/backup")
def export_backup(db: Session = Depends(get_db), user: CurrentUser = Depends(require_admin)):
    data = backup.export_metadata(db)
    audit(db, user, "METADATA_EXPORTED", "metadata", None,
          rows={k: len(v) for k, v in data["tables"].items()})
    db.commit()
    stamp = datetime.now(timezone.utc).strftime("%Y%m%d-%H%M%S")
    return JSONResponse(data, headers={"Content-Disposition": f'attachment; filename="dbamanager-metadata-{stamp}.json"'})


@router.post("/restore")
async def import_backup(file: UploadFile = File(...), replace: bool = False, db: Session = Depends(get_db),
                        user: CurrentUser = Depends(require_admin)):
    settings = get_settings()
    if replace and not settings.metadata_restore_replace:
        raise HTTPException(403, "Reemplazar la metadata existente está deshabilitado (METADATA_RESTORE_REPLACE=false): "
                                 "borraría la bitácora actual. Solo se restaura sobre una metadata vacía")
    raw = await file.read(settings.restore_max_mb * 1024 * 1024 + 1)
    if len(raw) > settings.restore_max_mb * 1024 * 1024:
        raise HTTPException(413, f"El respaldo excede {settings.restore_max_mb} MB (RESTORE_MAX_MB)")
    try:
        data = json.loads(raw)
        counts = backup.import_metadata(db, data, replace)
    except (ValueError, KeyError) as exc:
        db.rollback()
        raise HTTPException(422, str(exc)) from exc
    audit(db, user, "METADATA_IMPORTED", "metadata", None, replace=replace, rows=counts, file=file.filename)
    db.commit()
    return {"ok": True, "rows": counts}
