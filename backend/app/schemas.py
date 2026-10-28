import json
from datetime import datetime
from typing import Literal

from pydantic import BaseModel, Field

from app.models import ChangeRequest, DbConnection


class ConnectionIn(BaseModel):
    name: str = Field(min_length=2, max_length=100)
    engine: str = "oracle"
    environment: str = Field(min_length=2, max_length=20)
    description: str | None = None
    host: str
    port: int = Field(ge=1, le=65535)
    service_name: str
    catalog_user: str
    catalog_password: str | None = None  # None en edición = conservar la actual
    exec_user: str | None = None
    exec_password: str | None = None
    allow_execution: bool = False
    use_tls: bool = False
    required_approvals: int = Field(1, ge=1, le=5)
    active: bool = True


class ConnectionOut(BaseModel):
    id: int
    name: str
    engine: str
    environment: str
    description: str | None
    host: str
    port: int
    service_name: str
    catalog_user: str
    exec_user: str | None
    has_exec_account: bool
    allow_execution: bool
    use_tls: bool = False
    required_approvals: int
    active: bool
    server_version: str | None = None

    @classmethod
    def of(cls, c: DbConnection) -> "ConnectionOut":
        return cls(
            id=c.id, name=c.name, engine=c.engine, environment=c.environment, description=c.description,
            host=c.host, port=c.port, service_name=c.service_name, catalog_user=c.catalog_user,
            exec_user=c.exec_user, has_exec_account=bool(c.exec_user and c.exec_password_enc),
            allow_execution=c.allow_execution, required_approvals=c.required_approvals, active=c.active,
            server_version=c.server_version, use_tls=bool(c.use_tls),
        )


class ProjectIn(BaseModel):
    code: str = Field(min_length=2, max_length=30)
    name: str = Field(min_length=2, max_length=200)
    description: str | None = None
    owner: str | None = None
    active: bool = True


class RolesIn(BaseModel):
    roles: list[str]


class ScopeIn(BaseModel):
    username: str
    connection_id: int
    schema_name: str = Field(min_length=1, max_length=128)


class RequestIn(BaseModel):
    title: str = Field(min_length=5, max_length=200)
    connection_id: int
    project_id: int | None = None
    reason: str = Field(min_length=10, max_length=4000, description="Por qué se necesita")
    purpose: str | None = Field(None, max_length=4000, description="Para qué / con qué fin")
    ticket_ref: str | None = Field(None, max_length=100)
    operations: list[dict] = Field(min_length=1)
    submit: bool = False


class PreviewIn(BaseModel):
    connection_id: int
    operations: list[dict] = Field(min_length=1)
    preflight: bool = False


class ImportSqlIn(BaseModel):
    connection_id: int
    sql: str = Field(min_length=1, max_length=500_000)
    default_schema: str | None = Field(None, max_length=128, description="Esquema para objetos sin prefijo en el script")


class ReviewIn(BaseModel):
    decision: Literal["APPROVED", "REJECTED", "CHANGES_REQUESTED"]
    comment: str | None = Field(None, max_length=4000)


class CommentIn(BaseModel):
    body: str = Field(min_length=1, max_length=4000)


class CancelIn(BaseModel):
    comment: str | None = Field(None, max_length=4000)


class ExecuteIn(BaseModel):
    confirm_code: str = Field(max_length=40)


class ManualExecutionIn(BaseModel):
    confirm_code: str = Field(max_length=40)
    notes: str = Field(max_length=4000)


def request_summary(r: ChangeRequest) -> dict:
    return {
        "id": r.id, "code": r.code, "title": r.title, "status": r.status,
        "connection": {"id": r.connection.id, "name": r.connection.name, "environment": r.connection.environment},
        "project": {"id": r.project.id, "code": r.project.code, "name": r.project.name} if r.project else None,
        "target_schemas": [s for s in (r.target_schemas or "").split(",") if s],
        "operation_types": sorted({o.op_type for o in r.operations}),
        "requested_by": r.requested_by, "created_at": r.created_at, "updated_at": r.updated_at,
        "submitted_at": r.submitted_at, "decided_at": r.decided_at, "executed_at": r.executed_at,
    }


def request_detail(r: ChangeRequest) -> dict:
    return {
        **request_summary(r),
        "reason": r.reason, "purpose": r.purpose, "ticket_ref": r.ticket_ref,
        "operations": [{
            "id": o.id, "seq": o.seq, "type": o.op_type, "schema": o.target_schema, "object_name": o.object_name,
            "payload": json.loads(o.payload), "statements": json.loads(o.statements),
            "status": o.status, "message": o.message, "executed_at": o.executed_at,
            "statements_done": o.statements_done or 0,
        } for o in r.operations],
        "approvals": [{
            "approver": a.approver, "decision": a.decision, "comment": a.comment, "active": a.active,
            "decided_at": a.decided_at,
        } for a in r.approvals],
        "comments": [{"id": c.id, "author": c.author, "body": c.body, "created_at": c.created_at} for c in r.comments],
        "executions": [{
            "id": e.id, "executed_by": e.executed_by, "mode": e.mode, "status": e.status, "log": e.log,
            "notes": e.notes, "started_at": e.started_at, "finished_at": e.finished_at,
        } for e in r.executions],
        "required_approvals": r.connection.required_approvals,
    }


class AuditOut(BaseModel):
    id: int
    at: datetime
    username: str
    action: str
    entity_type: str
    entity_id: str | None
    details: dict | None
    ip: str | None
