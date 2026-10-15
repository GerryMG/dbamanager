import json

from sqlalchemy.orm import Session

from app.core.security import CurrentUser
from app.models import AuditLog


def audit(db: Session, user: CurrentUser | str, action: str, entity_type: str, entity_id=None, **details) -> None:
    """Agrega un evento a la bitácora (el commit lo hace quien llama, junto con el cambio)."""
    username = user if isinstance(user, str) else user.username
    ip = None if isinstance(user, str) else user.ip
    db.add(AuditLog(
        username=username, action=action, entity_type=entity_type,
        entity_id=None if entity_id is None else str(entity_id),
        details=json.dumps(details, default=str, ensure_ascii=False) if details else None, ip=ip,
    ))
