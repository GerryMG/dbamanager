#!/bin/bash
# Respaldo físico (Data Pump) del esquema de metadata DBAMGR en ./backups
# Uso: scripts/backup-metadata.sh            -> exporta
#      scripts/backup-metadata.sh restore F  -> importa el archivo F (.dmp en ./backups)
set -euo pipefail
cd "$(dirname "$0")/.."
source .env
STAMP=$(date +%Y%m%d-%H%M%S)
if [ "${1:-}" = "restore" ]; then
  FILE="${2:?Indica el archivo .dmp dentro de ./backups}"
  docker exec dbamanager-oracle impdp "DBAMGR/${DBAMGR_PASSWORD}@FREEPDB1" \
    directory=DBAMGR_BACKUP dumpfile="$(basename "$FILE")" logfile="impdp-${STAMP}.log" table_exists_action=replace
else
  docker exec dbamanager-oracle expdp "DBAMGR/${DBAMGR_PASSWORD}@FREEPDB1" \
    schemas=DBAMGR directory=DBAMGR_BACKUP dumpfile="dbamgr-${STAMP}.dmp" logfile="expdp-${STAMP}.log"
  echo "Respaldo: backups/dbamgr-${STAMP}.dmp"
fi
