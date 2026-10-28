#!/bin/bash
# Oracle 18c XE para pruebas de compatibilidad (perfil "compat"): cuentas de servicio + esquemas demo en XEPDB1
set -euo pipefail
cd /container-entrypoint-initdb.d
sqlplus -s / as sysdba <<SQL
ALTER SESSION SET CONTAINER = XEPDB1;
@/opt/dbamanager/cuentas_servicio.sql "${CATALOG_PASSWORD}" "${EXECUTOR_PASSWORD}"
SQL
sqlplus -s / as sysdba <<SQL
ALTER SESSION SET CONTAINER = XEPDB1;
@/opt/dbamanager/demo_schemas.sql.txt "${DEMO_PASSWORD}" XEPDB1
SQL
echo ">> Oracle 18c de compatibilidad listo"
