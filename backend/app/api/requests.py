import json

from fastapi import APIRouter, Depends, HTTPException, Query
from fastapi.responses import PlainTextResponse
from sqlalchemy import or_, select
from sqlalchemy.orm import Session

from app.core.db import get_db
from app.core.security import CurrentUser, get_current_user
from app.engines.base import Finding
from app.models import ApproverScope, AuditLog, ChangeRequest, Project
from app.schemas import (CancelIn, CommentIn, ExecuteIn, ImportSqlIn, ManualExecutionIn, PreviewIn, RequestIn, ReviewIn,
                         request_detail, request_summary)
from app.services import workflow
from app.services.connections import conn_info, engine_for, load_connection

router = APIRouter(prefix="/api/requests", tags=["requests"])


def _load(db: Session, request_id: int) -> ChangeRequest:
    req = db.get(ChangeRequest, request_id)
    if req is None:
        raise HTTPException(404, "Solicitud no encontrada")
    return req


def _check_project(db: Session, project_id: int | None) -> None:
    if project_id is not None:
        p = db.get(Project, project_id)
        if p is None or not p.active:
            raise HTTPException(422, "Proyecto inválido o inactivo")


def _findings(findings: list[Finding]) -> list[dict]:
    return [{"level": f.level, "message": f.message, "op_seq": f.op_seq} for f in findings]


@router.get("")
def list_requests(
    view: str = Query("all", pattern="^(all|mine|to_review|to_execute)$"),
    status: str | None = None,
    connection_id: int | None = None,
    project_id: int | None = None,
    q: str | None = None,
    limit: int = Query(200, le=1000),
    db: Session = Depends(get_db),
    user: CurrentUser = Depends(get_current_user),
):
    stmt = select(ChangeRequest).order_by(ChangeRequest.id.desc())
    if view == "mine":
        stmt = stmt.where(ChangeRequest.requested_by == user.username)
    elif view == "to_review":
        stmt = stmt.where(ChangeRequest.status == "PENDING_APPROVAL", ChangeRequest.requested_by != user.username)
    elif view == "to_execute":
        stmt = stmt.where(ChangeRequest.status.in_(workflow.EXECUTABLE))
    if status:
        stmt = stmt.where(ChangeRequest.status == status)
    if connection_id:
        stmt = stmt.where(ChangeRequest.connection_id == connection_id)
    if project_id:
        stmt = stmt.where(ChangeRequest.project_id == project_id)
    if q:
        like = f"%{q.upper()}%"
        stmt = stmt.where(or_(ChangeRequest.title.ilike(f"%{q}%"), ChangeRequest.target_schemas.like(like),
                              ChangeRequest.requested_by.ilike(f"%{q}%")))
    rows = db.scalars(stmt.limit(limit)).all()
    if view == "to_review":
        rows = [r for r in rows if workflow.can_review(db, user, r)
                and not any(a.active and a.approver == user.username for a in r.approvals)]
    elif view == "to_execute":
        rows = [r for r in rows if workflow.can_execute(db, user, r)]
    return [request_summary(r) for r in rows]


@router.post("/preview")
def preview(data: PreviewIn, db: Session = Depends(get_db), user: CurrentUser = Depends(get_current_user)):
    """Valida operaciones y devuelve el SQL que se generaría (sin guardar ni ejecutar)."""
    conn = load_connection(db, data.connection_id)
    parsed = workflow.parse_operations(data.operations)
    rendered = workflow.render_operations(db, conn, parsed)
    engine = engine_for(conn)
    result = {
        "operations": [{"seq": i, "type": op.type, "schema": op.schema_name, "object_name": op.object_name,
                        "statements": [s.to_dict() for s in sts]}
                       for i, (op, sts) in enumerate(zip(parsed, rendered), start=1)],
        "script": "\n\n".join(engine.script(sts) for sts in rendered),
        "findings": [],
    }
    if data.preflight:
        result["findings"] = _findings(engine.preflight(conn_info(conn, "catalog", user.username), parsed))
    return result


@router.post("/import-sql")
def import_sql(data: ImportSqlIn, db: Session = Depends(get_db), user: CurrentUser = Depends(get_current_user)):
    """Convierte un script SQL en operaciones visuales editables (no guarda ni ejecuta nada)."""
    conn = load_connection(db, data.connection_id)
    engine = engine_for(conn)
    items = engine.import_script(conn_info(conn, "catalog", user.username), data.sql, data.default_schema or "")
    out, fallen_back = [], set()
    for item in items:
        op, notes = item["op"], item["notes"]
        if item["companion"] and item["source"] in fallen_back:
            continue  # la restricción ya va dentro del CREATE TABLE que quedó como SQL
        # Se valida con un esquema provisional: el usuario puede completar el esquema después.
        probe = {**op, "schema": op.get("schema") or "X"}
        try:
            workflow.parse_operations([probe])
        except HTTPException as exc:
            errors = "; ".join(e["message"] for e in exc.detail["errors"])
            if op["type"] != "CUSTOM_SQL":
                notes = [*notes, f"No se pudo representar visualmente ({errors}); queda como SQL personalizado"]
                fallen_back.add(item["source"])
                op = {"type": "CUSTOM_SQL", "schema": op.get("schema") or "", "description": "Importado del script",
                      "sql": ";\n".join([item["source"], *item["absorbed"]])}
            else:
                notes = [*notes, errors]
        out.append({"op": op, "notes": notes})
    return {
        "operations": out,
        "recognized": sum(1 for o in out if o["op"]["type"] != "CUSTOM_SQL"),
        "custom": sum(1 for o in out if o["op"]["type"] == "CUSTOM_SQL"),
    }


@router.post("")
def create(data: RequestIn, db: Session = Depends(get_db), user: CurrentUser = Depends(get_current_user)):
    conn = load_connection(db, data.connection_id)
    _check_project(db, data.project_id)
    req = workflow.create_request(db, user, data.model_dump(), conn)
    return request_detail(req)


@router.get("/{request_id}")
def detail(request_id: int, db: Session = Depends(get_db), user: CurrentUser = Depends(get_current_user)):
    req = _load(db, request_id)
    out = request_detail(req)
    out["permissions"] = workflow.permissions_for(db, user, req)
    out["eligible_reviewers"] = _eligible_reviewers(db, req)
    return out


@router.put("/{request_id}")
def update(request_id: int, data: RequestIn, db: Session = Depends(get_db),
           user: CurrentUser = Depends(get_current_user)):
    req = _load(db, request_id)
    conn = load_connection(db, data.connection_id)
    _check_project(db, data.project_id)
    workflow.update_request(db, user, req, data.model_dump(), conn)
    return request_detail(req)


@router.post("/{request_id}/submit")
def submit(request_id: int, db: Session = Depends(get_db), user: CurrentUser = Depends(get_current_user)):
    req = _load(db, request_id)
    workflow.submit(db, user, req)
    db.commit()
    return request_detail(req)


@router.post("/{request_id}/review")
def review(request_id: int, data: ReviewIn, db: Session = Depends(get_db),
           user: CurrentUser = Depends(get_current_user)):
    req = _load(db, request_id)
    workflow.review(db, user, req, data.decision, data.comment)
    return request_detail(req)


@router.post("/{request_id}/cancel")
def cancel(request_id: int, data: CancelIn, db: Session = Depends(get_db),
           user: CurrentUser = Depends(get_current_user)):
    req = _load(db, request_id)
    workflow.cancel(db, user, req, data.comment)
    return request_detail(req)


@router.post("/{request_id}/comments")
def comment(request_id: int, data: CommentIn, db: Session = Depends(get_db),
            user: CurrentUser = Depends(get_current_user)):
    req = _load(db, request_id)
    workflow.add_comment(db, user, req, data.body)
    return request_detail(req)


@router.get("/{request_id}/preflight")
def preflight(request_id: int, db: Session = Depends(get_db), user: CurrentUser = Depends(get_current_user)):
    req = _load(db, request_id)
    parsed = workflow.parse_operations([json.loads(o.payload) for o in req.operations])
    engine = engine_for(req.connection)
    return _findings(engine.preflight(conn_info(req.connection, "catalog", user.username), parsed))


@router.get("/{request_id}/script", response_class=PlainTextResponse)
def script(request_id: int, db: Session = Depends(get_db), user: CurrentUser = Depends(get_current_user)):
    req = _load(db, request_id)
    return PlainTextResponse(workflow.build_script(req), headers={
        "Content-Disposition": f'attachment; filename="{req.code}.sql"'})


@router.post("/{request_id}/execute")
def execute(request_id: int, data: ExecuteIn, db: Session = Depends(get_db),
            user: CurrentUser = Depends(get_current_user)):
    req = _load(db, request_id)
    result = workflow.execute(db, user, req, data.confirm_code)
    return {**result, "request": request_detail(req)}


@router.post("/{request_id}/mark-executed")
def mark_executed(request_id: int, data: ManualExecutionIn, db: Session = Depends(get_db),
                  user: CurrentUser = Depends(get_current_user)):
    req = _load(db, request_id)
    workflow.mark_executed(db, user, req, data.confirm_code, data.notes)
    return request_detail(req)


@router.get("/{request_id}/history")
def history(request_id: int, db: Session = Depends(get_db), user: CurrentUser = Depends(get_current_user)):
    rows = db.scalars(select(AuditLog).where(AuditLog.entity_type == "request", AuditLog.entity_id == str(request_id))
                      .order_by(AuditLog.at)).all()
    return [{"at": r.at, "username": r.username, "action": r.action,
             "details": json.loads(r.details) if r.details else None} for r in rows]


def _eligible_reviewers(db: Session, req: ChangeRequest) -> list[str]:
    """DBAs con alcance sobre todos los esquemas (además de los dba_admin)."""
    schemas = workflow.schemas_of(req)
    rows = db.scalars(select(ApproverScope).where(ApproverScope.connection_id == req.connection_id)).all()
    by_user: dict[str, set[str]] = {}
    for r in rows:
        by_user.setdefault(r.username, set()).add(r.schema_name.upper())
    return sorted(u for u, s in by_user.items() if ("*" in s or schemas <= s) and u != req.requested_by)
