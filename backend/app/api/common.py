from fastapi import APIRouter, Depends
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.core.db import get_db
from app.core.security import CurrentUser, get_current_user
from app.engines import ENGINES
from app.models import ApproverScope, DbConnection, Project
from app.operations import DESTRUCTIVE_OPS, OBJECT_PRIVILEGES, OPERATION_LABELS, SYSTEM_PRIVILEGES
from app.schemas import ConnectionOut

router = APIRouter(prefix="/api", tags=["common"])


@router.get("/me")
def me(db: Session = Depends(get_db), user: CurrentUser = Depends(get_current_user)):
    scopes = db.scalars(select(ApproverScope).where(ApproverScope.username == user.username)).all()
    return {
        "username": user.username, "full_name": user.full_name, "email": user.email,
        "roles": sorted(user.roles), "is_admin": user.is_admin, "is_dba": user.is_dba,
        "is_dba_admin": user.is_dba_admin,
        "scopes": [{"connection_id": s.connection_id, "schema_name": s.schema_name} for s in scopes],
    }


@router.get("/meta")
def meta(user: CurrentUser = Depends(get_current_user)):
    return {
        "engines": [{"key": e.key, "label": e.label, "data_types": e.data_types} for e in ENGINES.values()],
        "operations": [{"type": k, "label": v, "destructive": k in DESTRUCTIVE_OPS} for k, v in OPERATION_LABELS.items()],
        "object_privileges": OBJECT_PRIVILEGES,
        "system_privileges": SYSTEM_PRIVILEGES,
    }


@router.get("/connections", response_model=list[ConnectionOut])
def connections(db: Session = Depends(get_db), user: CurrentUser = Depends(get_current_user)):
    # `== True` (no `.is_(True)`) para que sea portable: Oracle no acepta "IS 1"
    rows = db.scalars(select(DbConnection).where(DbConnection.active == True)  # noqa: E712
                      .order_by(DbConnection.name))
    return [ConnectionOut.of(c) for c in rows]


@router.get("/projects")
def projects(db: Session = Depends(get_db), user: CurrentUser = Depends(get_current_user)):
    rows = db.scalars(select(Project).order_by(Project.code)).all()
    return [{"id": p.id, "code": p.code, "name": p.name, "description": p.description, "owner": p.owner,
             "active": p.active} for p in rows]
