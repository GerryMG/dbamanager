#!/bin/bash
# Inicialización del contenedor Oracle Free (solo corre la primera vez que se crea el volumen).
#
#   FREEPDB1 -> metadata de DBA Manager (usuario DBAMGR, privilegios mínimos)
#   DEVPDB   -> base administrada "DEV"  (esquemas demo + cuentas catálogo/ejecutora)
#   PRODPDB  -> base administrada "PROD" (esquemas demo + cuentas catálogo/ejecutora)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

run_sql() {
  sqlplus -s / as sysdba <<EOF
WHENEVER SQLERROR EXIT SQL.SQLCODE
SET ECHO OFF FEEDBACK ON
$1
EXIT
EOF
}

echo ">> Creando PDBs administradas (DEVPDB, PRODPDB)"
run_sql "
ALTER SYSTEM SET db_create_file_dest='/opt/oracle/oradata' SCOPE=BOTH;
CREATE PLUGGABLE DATABASE DEVPDB  ADMIN USER pdbadmin IDENTIFIED BY \"${ORACLE_PASSWORD}\";
CREATE PLUGGABLE DATABASE PRODPDB ADMIN USER pdbadmin IDENTIFIED BY \"${ORACLE_PASSWORD}\";
ALTER PLUGGABLE DATABASE DEVPDB OPEN;
ALTER PLUGGABLE DATABASE PRODPDB OPEN;
ALTER PLUGGABLE DATABASE ALL SAVE STATE;
"

echo ">> Usuario de metadata DBAMGR en FREEPDB1"
run_sql "
ALTER SESSION SET CONTAINER = FREEPDB1;
CREATE USER DBAMGR IDENTIFIED BY \"${DBAMGR_PASSWORD}\" DEFAULT TABLESPACE USERS QUOTA UNLIMITED ON USERS;
-- Solo lo necesario para administrar sus propias tablas. Nada de privilegios ANY.
GRANT CREATE SESSION, CREATE TABLE, CREATE SEQUENCE, CREATE VIEW TO DBAMGR;
-- Directorio para respaldos Data Pump del esquema de metadata
CREATE OR REPLACE DIRECTORY DBAMGR_BACKUP AS '/backups';
GRANT READ, WRITE ON DIRECTORY DBAMGR_BACKUP TO DBAMGR;
"

for PDB in DEVPDB PRODPDB; do
  echo ">> Cuentas de servicio y esquemas demo en ${PDB}"
  run_sql "
ALTER SESSION SET CONTAINER = ${PDB};
CREATE TABLESPACE USERS DATAFILE SIZE 100M AUTOEXTEND ON NEXT 50M;
ALTER DATABASE DEFAULT TABLESPACE USERS;

-- Cuenta de catálogo: solo lee el diccionario. Sin SELECT ANY TABLE => no puede ver datos.
CREATE USER DBAMGR_CATALOG IDENTIFIED BY \"${CATALOG_PASSWORD}\";
GRANT CREATE SESSION, SELECT_CATALOG_ROLE TO DBAMGR_CATALOG;

-- Cuenta ejecutora: privilegios amplios de DDL. DBA Manager solo la usa para ejecutar
-- solicitudes aprobadas y confirmadas por un DBA.
CREATE USER DBAMGR_EXEC IDENTIFIED BY \"${EXECUTOR_PASSWORD}\";
GRANT CREATE SESSION, SELECT_CATALOG_ROLE TO DBAMGR_EXEC;
GRANT CREATE ANY TABLE, ALTER ANY TABLE, DROP ANY TABLE, COMMENT ANY TABLE,
      CREATE ANY INDEX, ALTER ANY INDEX, DROP ANY INDEX,
      CREATE ANY VIEW, DROP ANY VIEW,
      CREATE ANY SEQUENCE, ALTER ANY SEQUENCE, DROP ANY SEQUENCE, SELECT ANY SEQUENCE,
      CREATE ANY SYNONYM, DROP ANY SYNONYM, CREATE PUBLIC SYNONYM, DROP PUBLIC SYNONYM,
      CREATE ANY PROCEDURE, ALTER ANY PROCEDURE, DROP ANY PROCEDURE, EXECUTE ANY PROCEDURE,
      CREATE ANY TRIGGER, ALTER ANY TRIGGER, DROP ANY TRIGGER,
      CREATE ANY MATERIALIZED VIEW, ALTER ANY MATERIALIZED VIEW, DROP ANY MATERIALIZED VIEW,
      -- Necesario para crear logs/vistas materializadas e índices de dominio sobre tablas de otros
      -- esquemas. Solo la usa la cuenta ejecutora; el explorador usa DBAMGR_CATALOG (sin acceso a datos).
      READ ANY TABLE,
      GRANT ANY OBJECT PRIVILEGE, GRANT ANY PRIVILEGE, GRANT ANY ROLE,
      CREATE USER, ALTER USER, DROP USER
   TO DBAMGR_EXEC;
"
  sqlplus -s / as sysdba <<EOF
ALTER SESSION SET CONTAINER = ${PDB};
@${SCRIPT_DIR}/demo_schemas.sql.txt "${DEMO_PASSWORD}" "${PDB}"
EOF
done

echo ">> Inicialización de DBA Manager completa"
