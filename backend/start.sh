#!/bin/sh
# Arranque de DBA Manager (contenedor o local): verifica servidores externos, migra la metadata y sirve la app.
set -e
cd "$(dirname "$0")"
python -m app.check
alembic upgrade head
python -m app.seed
exec uvicorn app.main:app --host 0.0.0.0 --port "${PORT:-8080}" --proxy-headers --forwarded-allow-ips='*' \
     --log-level "${LOG_LEVEL:-info}"
