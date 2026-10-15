"""Exploración de estructura (solo diccionario de datos, nunca datos de negocio)."""
from fastapi import APIRouter, Depends, Query
from sqlalchemy.orm import Session

from app.core.db import get_db
from app.core.security import CurrentUser, get_current_user
from app.operations import ident
from app.services.connections import conn_info, engine_for, load_connection

router = APIRouter(prefix="/api/explorer/{connection_id}", tags=["explorer"])


def _ctx(connection_id: int, db: Session, user: CurrentUser):
    conn = load_connection(db, connection_id)
    return engine_for(conn), conn_info(conn, "catalog", app_user=user.username)


@router.get("/schemas")
def schemas(connection_id: int, db: Session = Depends(get_db), user: CurrentUser = Depends(get_current_user)):
    engine, info = _ctx(connection_id, db, user)
    return engine.list_schemas(info)


@router.get("/schemas/{schema}/objects")
def objects(connection_id: int, schema: str, db: Session = Depends(get_db),
            user: CurrentUser = Depends(get_current_user)):
    engine, info = _ctx(connection_id, db, user)
    return engine.list_objects(info, ident(schema))


@router.get("/schemas/{schema}/tables/{name}")
def table(connection_id: int, schema: str, name: str, db: Session = Depends(get_db),
          user: CurrentUser = Depends(get_current_user)):
    engine, info = _ctx(connection_id, db, user)
    return engine.describe_table(info, ident(schema), ident(name))


@router.get("/schemas/{schema}/user")
def schema_user(connection_id: int, schema: str, db: Session = Depends(get_db),
                user: CurrentUser = Depends(get_current_user)):
    engine, info = _ctx(connection_id, db, user)
    return engine.describe_user(info, ident(schema))


@router.get("/schemas/{schema}/ddl")
def ddl(connection_id: int, schema: str, object_type: str = Query(alias="type"), name: str = Query(),
        db: Session = Depends(get_db), user: CurrentUser = Depends(get_current_user)):
    engine, info = _ctx(connection_id, db, user)
    return {"ddl": engine.get_ddl(info, ident(schema), object_type, ident(name))}


@router.get("/search")
def search(connection_id: int, q: str = Query(min_length=2, max_length=60), db: Session = Depends(get_db),
           user: CurrentUser = Depends(get_current_user)):
    engine, info = _ctx(connection_id, db, user)
    return engine.search(info, q.strip())
