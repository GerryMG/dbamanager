#!/bin/bash
# Crea (o actualiza) en Keycloak el cliente confidencial que DBA Manager usa para administrar roles,
# y crea los 4 roles de la app si no existen. Sirve para el Keycloak incluido o uno corporativo.
#
# Lee .env: KEYCLOAK_REALM, KEYCLOAK_ADMIN_CLIENT_ID, KEYCLOAK_ADMIN_CLIENT_SECRET, ROLE_* y KEYCLOAK_ROLES_SOURCE.
# Variables para conectarse como administrador de Keycloak (no se guardan):
#   KC_SETUP_URL   URL de Keycloak accesible desde este servidor (por defecto http://localhost:${KEYCLOAK_PORT:-8180})
#   KC_SETUP_USER  usuario administrador (por defecto "admin")
#   KC_SETUP_PASS  contraseña (por defecto KEYCLOAK_ADMIN_PASSWORD de .env)
#   KC_SETUP_REALM realm donde vive ese administrador (por defecto "master")
set -euo pipefail
cd "$(dirname "$0")/../.."
set -a; source .env; set +a

URL="${KC_SETUP_URL:-http://localhost:${KEYCLOAK_PORT:-8180}}"
REALM="${KEYCLOAK_REALM:-dbamanager}"
CLIENT="${KEYCLOAK_ADMIN_CLIENT_ID:?Define KEYCLOAK_ADMIN_CLIENT_ID en .env}"
SECRET="${KEYCLOAK_ADMIN_CLIENT_SECRET:?Define KEYCLOAK_ADMIN_CLIENT_SECRET en .env}"
SOURCE="${KEYCLOAK_ROLES_SOURCE:-realm}"
ROLES_CLIENT="${KEYCLOAK_ROLES_CLIENT:-${KEYCLOAK_CLIENT_ID:-dbamanager-web}}"

export KCPASS="${KC_SETUP_PASS:-${KEYCLOAK_ADMIN_PASSWORD}}"
kc() {
  docker run --rm -i --network host -e HOME=/tmp -e KCPASS --entrypoint /bin/bash quay.io/keycloak/keycloak:26.0 -c \
    "/opt/keycloak/bin/kcadm.sh config credentials --server '$URL' --realm '${KC_SETUP_REALM:-master}' \
       --user '${KC_SETUP_USER:-admin}' --password \"\$KCPASS\" >/dev/null && /opt/keycloak/bin/kcadm.sh $1"
}

echo ">> Cliente $CLIENT en realm $REALM"
ID=$(kc "get clients -r $REALM -q clientId=$CLIENT --fields id --format csv --noquotes" | tail -1)
if [ -z "$ID" ]; then
  kc "create clients -r $REALM -s clientId=$CLIENT -s name='DBA Manager (administración de roles)' -s enabled=true \
      -s publicClient=false -s serviceAccountsEnabled=true -s standardFlowEnabled=false \
      -s directAccessGrantsEnabled=false -s implicitFlowEnabled=false -s secret=$SECRET"
else
  kc "update clients/$ID -r $REALM -s secret=$SECRET -s serviceAccountsEnabled=true -s publicClient=false"
fi

echo ">> Permisos mínimos de la cuenta de servicio (realm-management)"
kc "add-roles -r $REALM --uusername service-account-$CLIENT --cclientid realm-management \
    --rolename view-users --rolename query-users --rolename manage-users --rolename view-clients"

echo ">> Roles de la app ($SOURCE)"
for ROLE in "${ROLE_REQUESTER:-solicitante}" "${ROLE_DBA:-dba}" "${ROLE_DBA_ADMIN:-dba_admin}" "${ROLE_ADMIN:-admin}"; do
  if [ "$SOURCE" = "client" ]; then
    RC=$(kc "get clients -r $REALM -q clientId=$ROLES_CLIENT --fields id --format csv --noquotes" | tail -1)
    kc "create clients/$RC/roles -r $REALM -s name=$ROLE" 2>/dev/null && echo "   creado $ROLE" || echo "   ya existe $ROLE"
  else
    kc "create roles -r $REALM -s name=$ROLE" 2>/dev/null && echo "   creado $ROLE" || echo "   ya existe $ROLE"
  fi
done
echo ">> Listo. Reinicia el backend: docker compose up -d backend"
