import json
import logging
from contextlib import asynccontextmanager
from urllib.parse import urlsplit

import oracledb
from fastapi import FastAPI, HTTPException, Request, Response
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, JSONResponse
from fastapi.staticfiles import StaticFiles

from app.api import admin, common, explorer, requests
from app.core.config import get_settings
from app.core.db import SessionLocal
from app.services import workflow

log = logging.getLogger("dbamanager")


@asynccontextmanager
async def lifespan(_: FastAPI):
    with SessionLocal() as db:
        n = workflow.recover_interrupted(db)
        if n:
            log.warning("%s solicitud(es) quedaron a mitad de ejecución y se marcaron como FAILED", n)
    yield


_settings = get_settings()
app = FastAPI(title="DBA Manager", version="0.1.0", lifespan=lifespan,
              docs_url="/api/docs" if _settings.api_docs_enabled else None,
              redoc_url=None,
              openapi_url="/api/openapi.json" if _settings.api_docs_enabled else None)


def _origin(url: str) -> str:
    parts = urlsplit(url)
    return f"{parts.scheme}://{parts.netloc}" if parts.scheme and parts.netloc else ""


# Política de contenido: solo scripts propios; conexiones solo a la propia API y a Keycloak.
_CSP = "; ".join([
    "default-src 'self'",
    "script-src 'self'",
    "style-src 'self' 'unsafe-inline'",  # estilos en línea de la librería de componentes
    "img-src 'self' data:",
    "font-src 'self' data:",
    f"connect-src 'self' {_origin(_settings.keycloak_public_url)}".strip(),
    "frame-ancestors 'none'",
    "base-uri 'self'",
    "form-action 'self'",
    "object-src 'none'",
])


@app.middleware("http")
async def security_headers(request: Request, call_next):
    response = await call_next(request)
    h = response.headers
    h.setdefault("Content-Security-Policy", _CSP)
    h.setdefault("X-Content-Type-Options", "nosniff")
    h.setdefault("X-Frame-Options", "DENY")
    h.setdefault("Referrer-Policy", "no-referrer")
    h.setdefault("Permissions-Policy", "camera=(), microphone=(), geolocation=(), payment=()")
    h.setdefault("Cross-Origin-Opener-Policy", "same-origin")
    if request.url.path.startswith("/api/"):
        h["Cache-Control"] = "no-store"  # respuestas con datos sensibles (SQL, bitácora, contraseñas generadas)
    if request.url.scheme == "https":
        h.setdefault("Strict-Transport-Security", "max-age=31536000; includeSubDomains")
    return response

origins = [o.strip() for o in get_settings().cors_origins.split(",") if o.strip()]
if origins:
    app.add_middleware(CORSMiddleware, allow_origins=origins, allow_methods=["*"], allow_headers=["*"],
                       allow_credentials=False)


@app.exception_handler(oracledb.Error)
async def oracle_error(_: Request, exc: oracledb.Error):
    log.warning("Error de base de datos administrada: %s", exc)
    return JSONResponse(status_code=502, content={"detail": f"Error en la base de datos: {exc}"})


@app.exception_handler(PermissionError)
async def forbidden(_: Request, exc: PermissionError):
    return JSONResponse(status_code=403, content={"detail": str(exc)})


@app.exception_handler(LookupError)
async def not_found(_: Request, exc: LookupError):
    return JSONResponse(status_code=404, content={"detail": str(exc)})


@app.exception_handler(ValueError)
async def bad_value(_: Request, exc: ValueError):
    return JSONResponse(status_code=422, content={"detail": str(exc)})


@app.get("/api/health")
def health():
    return {"status": "ok"}


for r in (common.router, explorer.router, requests.router, admin.router):
    app.include_router(r)


# ---------------------------------------------------------------------- frontend (misma aplicación)
@app.get("/config.js", include_in_schema=False)
def frontend_config() -> Response:
    """Configuración del navegador generada desde las variables de entorno (sin reconstruir el frontend)."""
    s = get_settings()
    cfg = {"keycloakUrl": s.keycloak_public_url.rstrip("/"), "keycloakRealm": s.keycloak_realm,
           "keycloakClient": s.keycloak_client_id}
    return Response(f"window.__CONFIG__ = {json.dumps(cfg)};", media_type="application/javascript",
                    headers={"Cache-Control": "no-store"})


_static = get_settings().static_path.resolve()
if (_static / "index.html").exists():
    if (_static / "assets").is_dir():
        app.mount("/assets", StaticFiles(directory=_static / "assets"), name="assets")

    @app.get("/{path:path}", include_in_schema=False)
    def spa(path: str) -> FileResponse:
        if path.startswith("api/"):
            raise HTTPException(404, "Ruta de API inexistente")
        candidate = (_static / path).resolve()
        if path and candidate.is_file() and _static in candidate.parents:
            return FileResponse(candidate)
        # Rutas del frontend (/requests/12, /admin, ...) -> index.html
        return FileResponse(_static / "index.html", headers={"Cache-Control": "no-store"})
else:
    log.warning("Frontend no compilado en %s: solo se sirve la API", _static)
