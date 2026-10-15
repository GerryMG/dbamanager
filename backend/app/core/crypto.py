"""Cifrado de credenciales de conexiones guardadas en la metadata."""
from cryptography.fernet import Fernet

from app.core.config import get_settings

_fernet = Fernet(get_settings().secrets_key.encode())


def encrypt(value: str | None) -> str | None:
    if value is None or value == "":
        return None
    return _fernet.encrypt(value.encode()).decode()


def decrypt(value: str | None) -> str | None:
    if not value:
        return None
    return _fernet.decrypt(value.encode()).decode()
