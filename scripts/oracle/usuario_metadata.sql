-- Usuario dueño de la metadata de DBA Manager, para un Oracle 12.1+ propio (no el de Docker).
-- Ejecutar como SYSDBA o DBA en la PDB / base donde vivirá la metadata:
--   SQL> @usuario_metadata.sql "PasswordDeDBAMGR" USERS
-- Luego en .env: METADATA_DSN=host:puerto/servicio  y  DBAMGR_PASSWORD=...
-- (las tablas las crea el backend al arrancar con Alembic).
WHENEVER SQLERROR EXIT SQL.SQLCODE
SET VERIFY OFF FEEDBACK ON

CREATE USER DBAMGR IDENTIFIED BY "&1" DEFAULT TABLESPACE &2 QUOTA UNLIMITED ON &2;
-- Solo lo necesario para administrar sus propias tablas. Nada de privilegios ANY.
GRANT CREATE SESSION, CREATE TABLE, CREATE SEQUENCE, CREATE VIEW TO DBAMGR;
