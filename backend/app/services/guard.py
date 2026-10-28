"""Reglas de seguridad de las solicitudes: esquemas protegidos, operaciones de alto riesgo y sello
criptográfico de lo aprobado.
"""
import hashlib
import hmac
import json

from fastapi import HTTPException

from app.core.config import get_settings
from app.models import ChangeRequest, DbConnection

# Esquemas internos de Oracle: nunca pueden ser objeto de una solicitud (además se rechaza en la
# validación previa todo esquema marcado ORACLE_MAINTAINED en el diccionario).
ORACLE_INTERNAL = {
    "SYS", "SYSTEM", "SYSBACKUP", "SYSDG", "SYSKM", "SYSRAC", "SYS$UMF", "AUDSYS", "OUTLN", "DBSNMP", "APPQOSSYS",
    "GSMADMIN_INTERNAL", "GSMCATUSER", "GSMUSER", "GSMROOTUSER", "GGSYS", "DIP", "REMOTE_SCHEDULER_AGENT",
    "ANONYMOUS", "XS$NULL", "XDB", "CTXSYS", "MDSYS", "MDDATA", "ORDSYS", "ORDDATA", "ORDPLUGINS",
    "SI_INFORMTN_SCHEMA", "OLAPSYS", "LBACSYS", "DVSYS", "DVF", "WMSYS", "OJVMSYS", "ORACLE_OCM", "DGPDB_INT",
    "DBSFWUSER", "PDBADMIN", "VECSYS", "BAASSYS", "GGSHAREDCAP", "FLOWS_FILES", "APEX_PUBLIC_USER",
}

# Operaciones cuyo alcance real no se puede verificar (código o SQL libre) o que afectan a todos:
# solo un DBA principal (dba_admin) puede aprobarlas y ejecutarlas.
HIGH_RISK_OPS = {"CUSTOM_SQL", "CREATE_OR_REPLACE_CODE", "CREATE_USER"}


def protected_schemas(conn: DbConnection) -> set[str]:
    s = get_settings()
    extra = {x.strip().upper() for x in s.protected_schemas.split(",") if x.strip()}
    service = {u.upper() for u in (conn.catalog_user, conn.exec_user, s.metadata_user) if u}
    return ORACLE_INTERNAL | service | extra


def op_schemas(op) -> set[str]:
    """Todos los esquemas que una operación modifica o referencia."""
    out = {op.schema_name}
    for attr in ("target_schema",):
        if getattr(op, attr, None):
            out.add(getattr(op, attr))
    for c in [getattr(op, "constraint", None), *(getattr(op, "constraints", None) or [])]:
        if c is not None and getattr(c, "ref_schema", None):
            out.add(c.ref_schema)
    return {x for x in out if x}


def check_protected(conn: DbConnection, parsed: list) -> None:
    """Rechaza operaciones sobre esquemas internos de Oracle, cuentas de servicio o la metadata."""
    blocked = protected_schemas(conn)
    errors = []
    for i, op in enumerate(parsed, start=1):
        hit = op_schemas(op) & blocked
        grantee = getattr(op, "grantee", None)
        if grantee and grantee.upper() in blocked:
            hit = hit | {grantee.upper()}
        if hit:
            errors.append({"op_seq": i, "field": "schema", "message":
                           f"Esquema protegido: {', '.join(sorted(hit))}. DBA Manager no permite solicitudes sobre esquemas "
                           "internos de Oracle, sus cuentas de servicio ni la metadata"})
    if errors:
        raise HTTPException(422, {"message": "Operaciones sobre esquemas protegidos", "errors": errors})


def requires_principal(req: ChangeRequest) -> bool:
    return any(op.op_type in HIGH_RISK_OPS or op.target_schema == "PUBLIC" for op in req.operations)


def fingerprint(req: ChangeRequest) -> str:
    """Sello HMAC (con SECRETS_KEY) del SQL exacto y de la base destino.

    Se guarda en cada aprobación y se verifica antes de ejecutar: si alguien cambia la conexión
    (host, servicio, cuenta ejecutora, TLS) o el SQL almacenado, la aprobación deja de valer.
    """
    c = req.connection
    payload = {
        "connection": [c.id, c.engine, c.host, c.port, c.service_name, c.exec_user, bool(c.use_tls)],
        "operations": [[op.seq, op.op_type, json.loads(op.statements)] for op in req.operations],
    }
    data = json.dumps(payload, sort_keys=True, ensure_ascii=False).encode()
    return hmac.new(get_settings().secrets_key.encode(), data, hashlib.sha256).hexdigest()
