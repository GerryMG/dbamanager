"""Modelo de metadata de DBA Manager.

Portátil entre motores (solo tipos genéricos de SQLAlchemy) para poder migrar la metadata.
Los JSON se guardan como texto (CLOB en Oracle).
"""
from datetime import datetime, timezone

from sqlalchemy import Boolean, DateTime, ForeignKey, Identity, Integer, String, Text, UniqueConstraint
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.core.db import Base


def utcnow() -> datetime:
    return datetime.now(timezone.utc)


class AppUser(Base):
    """Usuarios vistos vía OIDC (la fuente de verdad es Keycloak)."""

    __tablename__ = "app_users"
    username: Mapped[str] = mapped_column(String(150), primary_key=True)
    sub: Mapped[str | None] = mapped_column(String(100))
    full_name: Mapped[str | None] = mapped_column(String(200))
    email: Mapped[str | None] = mapped_column(String(200))
    roles: Mapped[str | None] = mapped_column(String(200))
    last_seen: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))


class UserSetting(Base):
    """Preferencias que solo el propio usuario puede cambiar (no hay API para cambiarlas a otro)."""

    __tablename__ = "user_settings"
    username: Mapped[str] = mapped_column(String(150), primary_key=True)
    # Ejecutar solicitudes desde DBA Manager con la cuenta privilegiada. Desactivado por defecto:
    # solo el propio DBA puede activarlo para sí mismo.
    can_execute: Mapped[bool] = mapped_column(Boolean, default=False)
    can_execute_changed_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))


class DbConnection(Base):
    """Base de datos administrada. Cada una puede estar en un servidor/motor distinto."""

    __tablename__ = "db_connections"
    id: Mapped[int] = mapped_column(Integer, Identity(), primary_key=True)
    name: Mapped[str] = mapped_column(String(100), unique=True)
    engine: Mapped[str] = mapped_column(String(30), default="oracle")
    environment: Mapped[str] = mapped_column(String(20))  # DEV, QA, PROD, ...
    description: Mapped[str | None] = mapped_column(String(500))
    host: Mapped[str] = mapped_column(String(200))
    port: Mapped[int] = mapped_column(Integer)
    service_name: Mapped[str] = mapped_column(String(200))
    # Cuenta de solo diccionario para explorar estructura (nunca datos)
    catalog_user: Mapped[str] = mapped_column(String(128))
    catalog_password_enc: Mapped[str | None] = mapped_column(Text)
    # Cuenta privilegiada que ejecuta solicitudes aprobadas (opcional)
    exec_user: Mapped[str | None] = mapped_column(String(128))
    exec_password_enc: Mapped[str | None] = mapped_column(Text)
    allow_execution: Mapped[bool] = mapped_column(Boolean, default=False)
    # Versión del servidor (p. ej. 19.21.0.0.0), detectada al conectar; define qué sintaxis se permite
    server_version: Mapped[str | None] = mapped_column(String(40))
    required_approvals: Mapped[int] = mapped_column(Integer, default=1)
    active: Mapped[bool] = mapped_column(Boolean, default=True)
    created_by: Mapped[str | None] = mapped_column(String(150))
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)


class Project(Base):
    __tablename__ = "projects"
    id: Mapped[int] = mapped_column(Integer, Identity(), primary_key=True)
    code: Mapped[str] = mapped_column(String(30), unique=True)
    name: Mapped[str] = mapped_column(String(200))
    description: Mapped[str | None] = mapped_column(String(1000))
    owner: Mapped[str | None] = mapped_column(String(150))
    active: Mapped[bool] = mapped_column(Boolean, default=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)


class ApproverScope(Base):
    """Esquemas que un DBA con rol `dba` puede aprobar/ejecutar. `*` = todos los de la conexión."""

    __tablename__ = "approver_scopes"
    __table_args__ = (UniqueConstraint("username", "connection_id", "schema_name"),)
    id: Mapped[int] = mapped_column(Integer, Identity(), primary_key=True)
    username: Mapped[str] = mapped_column(String(150))
    connection_id: Mapped[int] = mapped_column(ForeignKey("db_connections.id"))
    schema_name: Mapped[str] = mapped_column(String(128))
    granted_by: Mapped[str | None] = mapped_column(String(150))
    granted_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)


class ChangeRequest(Base):
    __tablename__ = "change_requests"
    id: Mapped[int] = mapped_column(Integer, Identity(), primary_key=True)
    title: Mapped[str] = mapped_column(String(200))
    connection_id: Mapped[int] = mapped_column(ForeignKey("db_connections.id"))
    project_id: Mapped[int | None] = mapped_column(ForeignKey("projects.id"))
    reason: Mapped[str] = mapped_column(Text)  # por qué se necesita
    purpose: Mapped[str | None] = mapped_column(Text)  # para qué / con qué fin
    ticket_ref: Mapped[str | None] = mapped_column(String(100))
    status: Mapped[str] = mapped_column(String(30), default="DRAFT")
    target_schemas: Mapped[str | None] = mapped_column(String(1000))  # CSV derivado de las operaciones
    requested_by: Mapped[str] = mapped_column(String(150))
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow, onupdate=utcnow)
    submitted_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    decided_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    executed_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))

    connection: Mapped[DbConnection] = relationship()
    project: Mapped[Project | None] = relationship()
    operations: Mapped[list["RequestOperation"]] = relationship(
        back_populates="request", order_by="RequestOperation.seq", cascade="all, delete-orphan"
    )
    approvals: Mapped[list["Approval"]] = relationship(order_by="Approval.decided_at", cascade="all, delete-orphan")
    comments: Mapped[list["RequestComment"]] = relationship(
        order_by="RequestComment.created_at", cascade="all, delete-orphan"
    )
    executions: Mapped[list["Execution"]] = relationship(order_by="Execution.started_at", cascade="all, delete-orphan")

    @property
    def code(self) -> str:
        return f"SOL-{self.id:06d}"


class RequestOperation(Base):
    __tablename__ = "request_operations"
    id: Mapped[int] = mapped_column(Integer, Identity(), primary_key=True)
    request_id: Mapped[int] = mapped_column(ForeignKey("change_requests.id"))
    seq: Mapped[int] = mapped_column(Integer)
    op_type: Mapped[str] = mapped_column(String(40))
    target_schema: Mapped[str] = mapped_column(String(128))
    object_name: Mapped[str | None] = mapped_column(String(128))
    payload: Mapped[str] = mapped_column(Text)  # JSON
    statements: Mapped[str] = mapped_column(Text)  # JSON: lista de sentencias generadas
    status: Mapped[str] = mapped_column(String(20), default="PENDING")  # PENDING / EXECUTED / FAILED
    # Sentencias de esta operación ya ejecutadas: un reintento continúa desde la siguiente
    statements_done: Mapped[int] = mapped_column(Integer, default=0)
    message: Mapped[str | None] = mapped_column(Text)
    executed_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))

    request: Mapped[ChangeRequest] = relationship(back_populates="operations")


class Approval(Base):
    __tablename__ = "approvals"
    id: Mapped[int] = mapped_column(Integer, Identity(), primary_key=True)
    request_id: Mapped[int] = mapped_column(ForeignKey("change_requests.id"))
    approver: Mapped[str] = mapped_column(String(150))
    decision: Mapped[str] = mapped_column(String(30))  # APPROVED / REJECTED / CHANGES_REQUESTED
    comment: Mapped[str | None] = mapped_column(Text)
    active: Mapped[bool] = mapped_column(Boolean, default=True)  # se invalida si la solicitud se re-edita
    decided_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)


class RequestComment(Base):
    __tablename__ = "request_comments"
    id: Mapped[int] = mapped_column(Integer, Identity(), primary_key=True)
    request_id: Mapped[int] = mapped_column(ForeignKey("change_requests.id"))
    author: Mapped[str] = mapped_column(String(150))
    body: Mapped[str] = mapped_column(Text)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)


class Execution(Base):
    __tablename__ = "executions"
    id: Mapped[int] = mapped_column(Integer, Identity(), primary_key=True)
    request_id: Mapped[int] = mapped_column(ForeignKey("change_requests.id"))
    executed_by: Mapped[str] = mapped_column(String(150))
    mode: Mapped[str] = mapped_column(String(20))  # AUTO (por la app) / MANUAL (DBA fuera de la app)
    status: Mapped[str] = mapped_column(String(20))  # SUCCESS / FAILED
    log: Mapped[str | None] = mapped_column(Text)
    notes: Mapped[str | None] = mapped_column(Text)
    started_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)
    finished_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))


class AuditLog(Base):
    __tablename__ = "audit_log"
    id: Mapped[int] = mapped_column(Integer, Identity(), primary_key=True)
    at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)
    username: Mapped[str] = mapped_column(String(150))
    action: Mapped[str] = mapped_column(String(60))
    entity_type: Mapped[str] = mapped_column(String(40))
    entity_id: Mapped[str | None] = mapped_column(String(60))
    details: Mapped[str | None] = mapped_column(Text)  # JSON
    ip: Mapped[str | None] = mapped_column(String(60))


ALL_TABLES_IN_ORDER = [
    AppUser,
    UserSetting,
    DbConnection,
    Project,
    ApproverScope,
    ChangeRequest,
    RequestOperation,
    Approval,
    RequestComment,
    Execution,
    AuditLog,
]
