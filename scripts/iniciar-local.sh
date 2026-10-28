#!/usr/bin/env bash
# Corre DBA Manager en esta máquina, sin Docker (Linux / macOS).
# Requisitos: Python 3.11+ y Node.js 18+. Configuración en .env (copiar de .env.example).
#   scripts/iniciar-local.sh            # instala lo necesario (la primera vez) y arranca
#   scripts/iniciar-local.sh --build    # fuerza volver a compilar el frontend
set -euo pipefail
cd "$(dirname "$0")/.."

[ -f .env ] || { echo "No existe .env: cópialo de .env.example y completa las variables"; exit 1; }
PY=$(command -v python3 || command -v python || true)
[ -n "$PY" ] || { echo "Falta Python 3.11 o superior"; exit 1; }
"$PY" -c 'import sys; sys.exit(0 if sys.version_info >= (3, 11) else 1)' || { echo "Se requiere Python 3.11+"; exit 1; }

if [ ! -x backend/.venv/bin/python ]; then
  echo ">> Creando entorno de Python (backend/.venv)"
  "$PY" -m venv backend/.venv || { echo "No se pudo crear el entorno: instala el paquete venv de Python (p. ej. python3-venv)"; exit 1; }
fi
echo ">> Instalando dependencias de Python"
backend/.venv/bin/pip install -q --disable-pip-version-check -r backend/requirements.txt

if [ ! -f frontend/dist/index.html ] || [ "${1:-}" = "--build" ]; then
  command -v npm >/dev/null || { echo "Falta Node.js 18+ (npm) para compilar el frontend"; exit 1; }
  echo ">> Compilando el frontend"
  (cd frontend && npm ci --no-audit --no-fund && npm run build)
fi

# PORT y LOG_LEVEL se pueden definir en .env (el resto lo lee la aplicación directamente de .env)
PORT=${PORT:-$(grep -E '^PORT=' .env | tail -1 | cut -d= -f2 || true)}
LOG_LEVEL=${LOG_LEVEL:-$(grep -E '^LOG_LEVEL=' .env | tail -1 | cut -d= -f2 || true)}
export PORT=${PORT:-8080} LOG_LEVEL=${LOG_LEVEL:-info}
export PATH="$PWD/backend/.venv/bin:$PATH"
echo ">> DBA Manager en http://localhost:$PORT"
exec backend/start.sh
