import logging
from contextlib import asynccontextmanager

import oracledb
from fastapi import FastAPI, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse

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


app = FastAPI(title="DBA Manager", version="0.1.0", docs_url="/api/docs", openapi_url="/api/openapi.json",
              lifespan=lifespan)

origins = [o.strip() for o in get_settings().cors_origins.split(",") if o.strip()]
if origins:
    app.add_middleware(CORSMiddleware, allow_origins=origins, allow_methods=["*"], allow_headers=["*"],
                       allow_credentials=False)


@app.exception_handler(oracledb.Error)
async def oracle_error(_: Request, exc: oracledb.Error):
    log.warning("Error de base de datos administrada: %s", exc)
    return JSONResponse(status_code=502, content={"detail": f"Error en la base de datos: {exc}"})


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
