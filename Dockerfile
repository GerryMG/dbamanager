# DBA Manager: una sola imagen (frontend compilado + backend). Toda la configuración llega por
# variables de entorno (ver .env.example). Corre sin root y con cualquier UID asignado.

# ---- 1) Compilar el frontend ----
# Node solo se usa para compilar (no queda en la imagen final). Versión fija con parches de seguridad.
FROM node:24.22.1-alpine AS frontend
WORKDIR /src/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY frontend/ ./
RUN npm run build

# ---- 2) Aplicación ----
FROM python:3.12-slim
ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    HOME=/tmp \
    PORT=8080
WORKDIR /app/backend
COPY backend/requirements.txt ./
RUN pip install -r requirements.txt
COPY backend/ ./
COPY --from=frontend /src/frontend/dist /app/static
# Archivos legibles para cualquier UID del grupo 0 (requisito habitual de plataformas como OpenShift)
RUN chmod +x start.sh && chgrp -R 0 /app && chmod -R g=u /app
USER 1001
EXPOSE 8080
CMD ["./start.sh"]
