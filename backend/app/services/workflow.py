"""Ciclo de vida de una solicitud de cambio.

    DRAFT ──submit──▶ PENDING_APPROVAL ──approve(n)──▶ APPROVED ──execute──▶ EXECUTED
      ▲                    │   │                          │
      └─ CHANGES_REQUESTED ◀┘   └──reject──▶ REJECTED      └──falla──▶ FAILED ──reintentar──▶ ...
    (cancelar posible antes de ejecutar)

Reglas:
  * Quien solicita nunca aprueba su propia solicitud.
  * `dba_admin` aprueba/ejecuta todo; `dba` solo si tiene alcance sobre TODOS los esquemas afectados.
  * El SQL aprobado es exactamente el que se ejecuta; editar la solicitud invalida aprobaciones.
  * Ejecutar exige confirmar tecleando el código de la solicitud.
"""
import json
from datetime import datetime, timezone

from fastapi import HTTPException
from pydantic import TypeAdapter, ValidationError
from sqlalchemy import select, update
from sqlalchemy.orm import Session

from app.core.security import CurrentUser
from app.engines.base import Statement
from app.models import (Approval, ApproverScope, ChangeRequest, DbConnection, Execution, RequestComment,
                        RequestOperation, UserSetting)
from app.operations import Operation
from app.services.audit import audit
from app.services.connections import conn_info, engine_for, server_version

EDITABLE = {"DRAFT", "CHANGES_REQUESTED"}
CANCELLABLE = {"DRAFT", "PENDING_APPROVAL", "CHANGES_REQUESTED", "APPROVED"}
EXECUTABLE = {"APPROVED", "FAILED"}

_op_adapter = TypeAdapter(Operation)

# Mensajes genéricos de pydantic -> español
_PYDANTIC_ES = [
    (r"^Field required$", "Campo obligatorio"),
    (r"^List should have at least (\d+) items? after validation.*$", r"Debe tener al menos \1 elemento(s)"),
    (r"^String should have at least (\d+) characters?$", r"Debe tener al menos \1 caracteres"),
    (r"^String should have at most (\d+) characters?$", r"Debe tener máximo \1 caracteres"),
    (r"^Input should be greater than or equal to (.+)$", r"Debe ser mayor o igual a \1"),
    (r"^Input should be less than or equal to (.+)$", r"Debe ser menor o igual a \1"),
    (r"^Input should be a valid integer.*$", "Debe ser un número entero"),
    (r"^Input should be a valid string$", "Debe ser texto"),
    (r"^Input should be (.+)$", r"Valor no permitido; opciones: \1"),
    (r"^Extra inputs are not permitted$", "Campo no permitido"),
    (r"^String should match pattern .*$", "Formato inválido"),
]


def _es(msg: str) -> str:
    import re

    msg = msg.removeprefix("Value error, ")
    for pat, rep in _PYDANTIC_ES:
        if re.match(pat, msg):
            return re.sub(pat, rep, msg)
    return msg


def now() -> datetime:
    return datetime.now(timezone.utc)


# ---------------------------------------------------------------- operaciones / SQL
def parse_operations(raw_ops: list[dict]) -> list:
    errors, parsed = [], []
    for i, raw in enumerate(raw_ops, start=1):
        try:
            parsed.append(_op_adapter.validate_python(raw))
        except ValidationError as exc:
            for e in exc.errors():
                loc = ".".join(str(p) for p in e["loc"][1:]) or "operación"
                errors.append({"op_seq": i, "field": loc, "message": _es(e["msg"])})
    if errors:
        raise HTTPException(422, {"message": "Operaciones inválidas", "errors": errors})
    return parsed


def render_operations(db: Session, conn: DbConnection, parsed: list) -> list[list[Statement]]:
    engine = engine_for(conn)
    version = server_version(db, conn)
    out, errors = [], []
    for i, op in enumerate(parsed, start=1):
        try:
            out.append(engine.render(op, version))
        except ValueError as exc:
            errors.append({"op_seq": i, "field": "", "message": str(exc)})
    if errors:
        raise HTTPException(422, {"message": "No se pudo generar el SQL", "errors": errors})
    return out


def statements_of(req: ChangeRequest, only_pending: bool = False) -> list[tuple[RequestOperation, list[Statement]]]:
    return [
        (op, [Statement.from_dict(s) for s in json.loads(op.statements)])
        for op in req.operations
        if not only_pending or op.status != "EXECUTED"
    ]


def build_script(req: ChangeRequest) -> str:
    engine = engine_for(req.connection)
    header = [
        f"-- {req.code}: {req.title}",
        f"-- Conexión: {req.connection.name} ({req.connection.environment}) {req.connection.host}/{req.connection.service_name}",
        f"-- Proyecto: {req.project.code + ' - ' + req.project.name if req.project else '(sin proyecto)'}",
        f"-- Solicitó: {req.requested_by}   Estado: {req.status}",
    ]
    approvers = [a.approver for a in req.approvals if a.active and a.decision == "APPROVED"]
    if approvers:
        header.append(f"-- Aprobó: {', '.join(approvers)}")
    header.append("-- Motivo: " + " ".join(req.reason.split())[:500])
    body = []
    for op, sts in statements_of(req):
        payload = json.loads(op.payload)
        body.append(f"-- [{op.seq}] {op.op_type} {op.target_schema}.{op.object_name or ''}  ({op.status})")
        if payload.get("type") == "CREATE_USER":
            body.append("-- Reemplaza {{PASSWORD}} por una contraseña segura antes de ejecutar")
        body.append(engine.script(sts))
    return "\n".join(header) + "\n\n" + "\n\n".join(body) + "\n"


def _store_operations(db: Session, req: ChangeRequest, raw_ops: list[dict]) -> None:
    parsed = parse_operations(raw_ops)
    rendered = render_operations(db, req.connection, parsed)
    req.operations.clear()
    db.flush()
    for seq, (op, sts) in enumerate(zip(parsed, rendered), start=1):
        req.operations.append(RequestOperation(
            seq=seq, op_type=op.type, target_schema=op.schema_name, object_name=op.object_name,
            payload=op.model_dump_json(by_alias=True),
            statements=json.dumps([s.to_dict() for s in sts], ensure_ascii=False),
            status="PENDING",
        ))
    req.target_schemas = ",".join(sorted({op.schema_name for op in parsed}))


# ---------------------------------------------------------------- permisos
def schemas_of(req: ChangeRequest) -> set[str]:
    return set(filter(None, (req.target_schemas or "").split(",")))


def has_scope(db: Session, user: CurrentUser, connection_id: int, schemas: set[str]) -> bool:
    if user.is_dba_admin:
        return True
    if not user.is_dba:
        return False
    rows = db.scalars(select(ApproverScope.schema_name).where(
        ApproverScope.username == user.username, ApproverScope.connection_id == connection_id)).all()
    allowed = {r.upper() for r in rows}
    return "*" in allowed or schemas <= allowed


def can_review(db: Session, user: CurrentUser, req: ChangeRequest) -> bool:
    return user.username != req.requested_by and has_scope(db, user, req.connection_id, schemas_of(req))


def can_execute(db: Session, user: CurrentUser, req: ChangeRequest) -> bool:
    return has_scope(db, user, req.connection_id, schemas_of(req))


def self_execution_enabled(db: Session, username: str) -> bool:
    """¿El usuario activó para sí mismo la ejecución desde DBA Manager? (desactivado por defecto)"""
    row = db.get(UserSetting, username)
    return bool(row and row.can_execute)


def set_self_execution(db: Session, user: CurrentUser, enabled: bool) -> None:
    """Solo modifica la preferencia del propio usuario: no existe forma de cambiarla para otra persona."""
    if enabled and not user.is_dba:
        raise HTTPException(403, "Solo un DBA puede activar la ejecución desde DBA Manager")
    row = db.get(UserSetting, user.username) or UserSetting(username=user.username)
    row.can_execute, row.can_execute_changed_at = enabled, now()
    db.merge(row)
    audit(db, user, "SELF_EXECUTION_ENABLED" if enabled else "SELF_EXECUTION_DISABLED", "user", user.username)
    db.commit()


def permissions_for(db: Session, user: CurrentUser, req: ChangeRequest) -> dict:
    owner = user.username == req.requested_by
    review = can_review(db, user, req)
    already = any(a.active and a.approver == user.username for a in req.approvals)
    return {
        "edit": owner and req.status in EDITABLE,
        "submit": owner and req.status in EDITABLE,
        "cancel": (owner or user.is_dba_admin) and req.status in CANCELLABLE,
        "review": review and req.status == "PENDING_APPROVAL" and not already,
        "execute": can_execute(db, user, req) and req.status in EXECUTABLE,
        "auto_execute": bool(req.connection.allow_execution and req.connection.exec_user),
        "self_execution": self_execution_enabled(db, user.username),
        "comment": True,
    }


# ---------------------------------------------------------------- acciones
def create_request(db: Session, user: CurrentUser, data: dict, conn: DbConnection) -> ChangeRequest:
    req = ChangeRequest(
        title=data["title"], connection=conn, connection_id=conn.id, project_id=data.get("project_id"),
        reason=data["reason"], purpose=data.get("purpose"), ticket_ref=data.get("ticket_ref"),
        status="DRAFT", requested_by=user.username,
    )
    db.add(req)
    db.flush()
    _store_operations(db, req, data["operations"])
    audit(db, user, "REQUEST_CREATED", "request", req.id, title=req.title, connection=conn.name,
          operations=[o.op_type for o in req.operations])
    if data.get("submit"):
        submit(db, user, req)
    db.commit()
    return req


def update_request(db: Session, user: CurrentUser, req: ChangeRequest, data: dict, conn: DbConnection) -> ChangeRequest:
    if user.username != req.requested_by or req.status not in EDITABLE:
        raise HTTPException(409, "Solo quien solicita puede editar, y solo en borrador o con cambios solicitados")
    req.title, req.reason, req.purpose = data["title"], data["reason"], data.get("purpose")
    req.ticket_ref, req.project_id = data.get("ticket_ref"), data.get("project_id")
    req.connection, req.connection_id = conn, conn.id
    _store_operations(db, req, data["operations"])
    for a in req.approvals:
        a.active = False
    audit(db, user, "REQUEST_UPDATED", "request", req.id, operations=[o.op_type for o in req.operations])
    if data.get("submit"):
        submit(db, user, req)
    db.commit()
    return req


def submit(db: Session, user: CurrentUser, req: ChangeRequest) -> None:
    if user.username != req.requested_by or req.status not in EDITABLE:
        raise HTTPException(409, f"No se puede enviar una solicitud en estado {req.status}")
    if not req.operations:
        raise HTTPException(422, "La solicitud no tiene operaciones")
    for a in req.approvals:
        a.active = False
    req.status, req.submitted_at = "PENDING_APPROVAL", now()
    audit(db, user, "REQUEST_SUBMITTED", "request", req.id)


def review(db: Session, user: CurrentUser, req: ChangeRequest, decision: str, comment: str | None) -> None:
    if req.status != "PENDING_APPROVAL":
        raise HTTPException(409, f"La solicitud está en estado {req.status}")
    if user.username == req.requested_by:
        raise HTTPException(403, "No puedes revisar tu propia solicitud")
    if not can_review(db, user, req):
        raise HTTPException(403, "No tienes alcance de aprobación sobre los esquemas: " + ", ".join(sorted(schemas_of(req))))
    if decision != "APPROVED" and not (comment and comment.strip()):
        raise HTTPException(422, "Rechazar o pedir cambios requiere un comentario")
    if any(a.active and a.approver == user.username for a in req.approvals):
        raise HTTPException(409, "Ya registraste tu decisión en esta solicitud")

    req.approvals.append(Approval(approver=user.username, decision=decision, comment=comment, active=True))
    if decision == "REJECTED":
        req.status, req.decided_at = "REJECTED", now()
    elif decision == "CHANGES_REQUESTED":
        req.status = "CHANGES_REQUESTED"
    else:
        approved = {a.approver for a in req.approvals if a.active and a.decision == "APPROVED"}
        if len(approved) >= max(1, req.connection.required_approvals):
            req.status, req.decided_at = "APPROVED", now()
    audit(db, user, f"REQUEST_{decision}", "request", req.id, comment=comment, new_status=req.status)
    db.commit()


def cancel(db: Session, user: CurrentUser, req: ChangeRequest, comment: str | None) -> None:
    if not ((user.username == req.requested_by or user.is_dba_admin) and req.status in CANCELLABLE):
        raise HTTPException(409, f"No se puede cancelar en estado {req.status}")
    req.status = "CANCELLED"
    audit(db, user, "REQUEST_CANCELLED", "request", req.id, comment=comment)
    db.commit()


def add_comment(db: Session, user: CurrentUser, req: ChangeRequest, body: str) -> RequestComment:
    c = RequestComment(author=user.username, body=body)
    req.comments.append(c)
    audit(db, user, "COMMENT_ADDED", "request", req.id)
    db.commit()
    return c


def _check_executable(db: Session, user: CurrentUser, req: ChangeRequest, confirm_code: str) -> None:
    if req.status not in EXECUTABLE:
        raise HTTPException(409, f"Solo se ejecutan solicitudes aprobadas (estado actual: {req.status})")
    if not can_execute(db, user, req):
        raise HTTPException(403, "No tienes alcance para ejecutar en estos esquemas")
    if (confirm_code or "").strip().upper() != req.code:
        raise HTTPException(422, f"Para confirmar escribe el código exacto de la solicitud ({req.code})")


def execute(db: Session, user: CurrentUser, req: ChangeRequest, confirm_code: str) -> dict:
    """Ejecuta con la cuenta privilegiada, estrictamente en el orden de las operaciones.

    * Cada operación y cada sentencia se ejecutan en secuencia; al primer error se detiene todo.
    * Un reintento continúa en la sentencia exacta donde se quedó (statements_done), sin repetir
      las que ya se aplicaron (el DDL de Oracle hace commit implícito).
    * La solicitud se bloquea en estado EXECUTING para que dos DBAs no la ejecuten a la vez.
    """
    _check_executable(db, user, req, confirm_code)
    conn = req.connection
    if not conn.allow_execution:
        raise HTTPException(409, "Esta conexión no permite ejecución desde DBA Manager; usa el script y marca como ejecutada")
    if not self_execution_enabled(db, user.username):
        raise HTTPException(403, "No tienes activada la ejecución desde DBA Manager. Solo tú puedes activarla, "
                                 "en tu perfil; mientras tanto usa el script y marca la solicitud como ejecutada")
    info = conn_info(conn, "exec", app_user=user.username)
    engine = engine_for(conn)

    previous_status = req.status
    locked = db.execute(
        update(ChangeRequest)
        .where(ChangeRequest.id == req.id, ChangeRequest.status.in_(EXECUTABLE))
        .values(status="EXECUTING")
    ).rowcount
    audit(db, user, "EXECUTION_STARTED", "request", req.id)
    db.commit()
    if not locked:
        raise HTTPException(409, "La solicitud ya se está ejecutando o cambió de estado")
    db.refresh(req)

    log_lines, secrets, ok = [], [], True
    started = now()
    try:
        for op in req.operations:  # ordenadas por seq
            if op.status == "EXECUTED":
                continue
            sts = [Statement.from_dict(x) for x in json.loads(op.statements)][op.statements_done or 0:]
            if op.statements_done:
                log_lines.append(f"[{op.seq}] reanudando en la sentencia {op.statements_done + 1}")
            results = engine.execute(info, sts, tag=req.code)
            for r in results:
                log_lines.append(f"[{op.seq}] {'OK   ' if r.ok else 'ERROR'} {r.sql.splitlines()[0][:150]}")
                if not r.ok:
                    log_lines.append(f"      {r.message}")
                if r.secret:
                    secrets.append({"operation": op.seq, "user": op.target_schema, "password": r.secret})
            done = sum(1 for r in results if r.ok)
            op.statements_done = (op.statements_done or 0) + done
            if results and done == len(sts):
                op.status, op.message, op.executed_at = "EXECUTED", "OK", now()
                db.commit()  # avance persistido operación por operación
            else:
                failed = next((r for r in results if not r.ok), None)
                op.status, op.message = "FAILED", failed.message if failed else "Sin resultado"
                ok = False
                break
    except Exception as exc:  # noqa: BLE001 - error de conexión u otro: se registra y la solicitud queda FAILED
        ok = False
        log_lines.append(f"      Error inesperado: {exc}")
    finally:
        req.status = "EXECUTED" if ok else "FAILED"
        if ok:
            req.executed_at = now()
        req.executions.append(Execution(
            executed_by=user.username, mode="AUTO", status="SUCCESS" if ok else "FAILED",
            log="\n".join(log_lines), started_at=started, finished_at=now(),
        ))
        # Las contraseñas generadas NO se guardan: solo se registra que se generaron.
        audit(db, user, "EXECUTION_FINISHED", "request", req.id, status=req.status, previous_status=previous_status,
              generated_passwords=[s["user"] for s in secrets])
        db.commit()
    return {"status": req.status, "log": "\n".join(log_lines), "secrets": secrets}


def recover_interrupted(db: Session) -> int:
    """Al arrancar: una solicitud en EXECUTING significa que el proceso se interrumpió a mitad."""
    rows = db.scalars(select(ChangeRequest).where(ChangeRequest.status == "EXECUTING")).all()
    for req in rows:
        req.status = "FAILED"
        audit(db, "sistema", "EXECUTION_INTERRUPTED", "request", req.id,
              note="El servicio se reinició durante la ejecución; revisa el avance y reintenta")
    db.commit()
    return len(rows)


def mark_executed(db: Session, user: CurrentUser, req: ChangeRequest, confirm_code: str, notes: str) -> None:
    """El DBA ejecutó el script fuera de la app y registra el resultado."""
    _check_executable(db, user, req, confirm_code)
    if not notes or not notes.strip():
        raise HTTPException(422, "Describe cómo/dónde se ejecutó (notas obligatorias)")
    for op in req.operations:
        if op.status != "EXECUTED":
            op.status, op.message, op.executed_at = "EXECUTED", "Ejecutado manualmente", now()
    req.status, req.executed_at = "EXECUTED", now()
    req.executions.append(Execution(
        executed_by=user.username, mode="MANUAL", status="SUCCESS", notes=notes,
        started_at=now(), finished_at=now(),
    ))
    audit(db, user, "EXECUTION_MANUAL", "request", req.id, notes=notes)
    db.commit()
