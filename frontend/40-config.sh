#!/bin/sh
# Genera config.js con las variables del contenedor (sin reconstruir la imagen)
cat > /usr/share/nginx/html/config.js <<EOC
window.__CONFIG__ = {
  keycloakUrl: "${KEYCLOAK_URL:-http://localhost:8180}",
  keycloakRealm: "${KEYCLOAK_REALM:-dbamanager}",
  keycloakClient: "${KEYCLOAK_CLIENT:-dbamanager-web}",
};
EOC
chmod 644 /usr/share/nginx/html/config.js
