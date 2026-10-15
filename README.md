# DBA Manager

Explorar **estructura** de bases de datos (nunca datos) y solicitar cambios — tablas, columnas, permisos, sinónimos, usuarios lectores, procedimientos — con **aprobación de DBA**, motivo, proyecto y bitácora completa de quién pidió, aprobó y ejecutó.

## Arquitectura

| Servicio | Puerto | Descripción |
|---|---|---|
| `frontend` | 8088 | React + Mantine, servido por nginx (proxy `/api` → backend) |
| `backend` | 8000 | FastAPI (`/api/docs`), python-oracledb thin, SQLAlchemy + Alembic |
| `keycloak` | 8180 | OIDC, realm `dbamanager` |
| `oracle` | 1521 | Oracle 23ai Free |

Oracle local:

- `FREEPDB1` → esquema **`DBAMGR`**: única dueña de la metadata de la app (privilegios mínimos, sin `ANY`).
- `DEVPDB` y `PRODPDB` → bases administradas de ejemplo (esquemas `VENTAS`, `RRHH`, `BI_LECTOR`), cada una con:
  - `DBAMGR_CATALOG`: `CREATE SESSION` + `SELECT_CATALOG_ROLE`. Solo diccionario; no puede leer datos.
  - `DBAMGR_EXEC`: privilegios `ANY` de DDL (más `READ ANY TABLE`, requerido por Oracle para crear vistas/logs materializados sobre tablas de otros esquemas). Solo se usa para ejecutar solicitudes **aprobadas** y confirmadas; nunca para explorar.

Cada conexión es independiente (host/puerto/servicio propios), así que puede apuntar a servidores distintos. Para agregar otro motor se implementa `EngineAdapter` (`backend/app/engines/base.py`) y se registra en `engines/__init__.py`.

## Arranque

```bash
cp .env.example .env    # y cambiar contraseñas / SECRETS_KEY / KEYCLOAK_PUBLIC_URL
docker compose up -d --build
```

`KEYCLOAK_PUBLIC_URL` debe ser la URL con la que el navegador llega a Keycloak (es el *issuer* de los tokens). App: `http://<host>:8088`.

Usuarios demo (contraseña `Cambiar.123`, definidos en `docker/keycloak/realm-dbamanager.json`):

| Usuario | Roles | Puede |
|---|---|---|
| `admin.dba` | dba_admin, admin | aprobar/ejecutar todo, administrar conexiones, alcances y respaldos |
| `luis.dba` | dba | aprobar/ejecutar solo en `VENTAS` y `BI_LECTOR` (alcance asignado) |
| `ana.dev`, `pedro.bi` | solicitante | explorar y solicitar |

## Despliegue por componentes (servidores separados)

Cada pieza puede estar en un servidor distinto; nada depende de nombres internos de Docker si se configuran las URLs. `COMPOSE_PROFILES` (en `.env`) decide qué se levanta en cada servidor:

| Componente | Local (por defecto) | En otro servidor |
|---|---|---|
| Metadata (`DBAMGR`) | perfil `oracle-local` | Quitar `oracle-local`, crear el usuario con `scripts/oracle/usuario_metadata.sql` y definir `METADATA_DSN=host:puerto/servicio` y `DBAMGR_PASSWORD`. Las tablas las crea el backend al arrancar. |
| Keycloak | perfil `keycloak` | Quitar `keycloak` y configurar `KEYCLOAK_PUBLIC_URL`, `KEYCLOAK_REALM`, `KEYCLOAK_CLIENT_ID`; `KEYCLOAK_INTERNAL_URL` vacío o la URL que vea el backend. |
| Bases administradas | demo en el Oracle local | Siempre separadas: cada conexión tiene su host/puerto/servicio. Cuentas con `scripts/oracle/cuentas_servicio.sql`. |
| Backend | servicio `backend` | Puede correr solo: `docker compose up -d backend` en su servidor. |
| Frontend | servicio `frontend` | `BACKEND_URL=http://servidor-backend:8000`. |

Con metadata propia usa `SEED_DEMO=false`, para no crear las conexiones demo.

Ejemplo de servidor con solo backend y frontend:

```bash
COMPOSE_PROFILES= docker compose up -d backend frontend
```

En ese caso, `METADATA_DSN` y las variables de Keycloak deben apuntar a sus servidores.

Probado con backend y frontend en redes distintas. La metadata estaba en otro Oracle (18c), Keycloak se accedió por su URL pública y la base administrada por IP: inicio de sesión, exploración, solicitud, aprobación, ejecución y gestión de roles funcionaron.

## Qué se puede solicitar (todo de forma visual)

| Área | Operaciones |
|---|---|
| Tablas | Crear tabla (normal o temporal global `ON COMMIT DELETE/PRESERVE ROWS`, tablespace, compresión, `LOGGING`), renombrar, comentarios, `READ ONLY/READ WRITE`, eliminar |
| Columnas | Agregar, modificar (solo se genera lo que cambia), renombrar, eliminar. Identidad `ALWAYS`/`BY DEFAULT`/`ON NULL` con inicio e incremento, `DEFAULT ON NULL`, columnas virtuales (con o sin tipo), invisibles, `VARCHAR2(n CHAR)` |
| Restricciones | PK (nombre y tablespace del índice), UNIQUE, FK (`ON DELETE CASCADE/SET NULL`, otro esquema), CHECK; `DEFERRABLE`, `INITIALLY DEFERRED`, `DISABLE`, `NOVALIDATE`. En la tabla nueva o sobre existentes: agregar, habilitar/deshabilitar, renombrar, eliminar (`CASCADE`, `KEEP/DROP INDEX`) |
| Índices | Normal, único, bitmap, de dominio (`INDEXTYPE ... PARAMETERS`), por función (expresiones), `ASC/DESC`, `REVERSE`, `COMPRESS n` / `ADVANCED LOW/HIGH`, `LOCAL`, invisible, `ONLINE`, tablespace. Reconstruir, visible/invisible, renombrar, eliminar |
| Particiones | `RANGE` (con `INTERVAL`), `LIST` (con `DEFAULT`), `HASH` (número o nombres), tablespace por partición; agregar y eliminar particiones |
| Otros objetos | Secuencias (crear/modificar con `RESTART`), logs de vista materializada, vistas materializadas, vistas, triggers, procedimientos, funciones, paquetes, tipos |
| Accesos | GRANT/REVOKE (incluye permisos por columna), sinónimos privados y públicos, usuarios lectores/owner |

Lo que no tiene formulario (subparticiones, índices globales particionados, `CREATE TABLE AS SELECT`, tablas IOT, nombres entre comillas con minúsculas) se maneja como SQL personalizado, igualmente con aprobación.

## Compatibilidad: Oracle 12.1 en adelante

- El explorador usa solo vistas del diccionario disponibles desde 12.1. Por ejemplo, los `CHECK` se leen de `SEARCH_CONDITION` (`LONG`, todas las versiones) y no de `SEARCH_CONDITION_VC`, que es 12.2+.
- Cada conexión guarda la **versión del servidor**, detectada al probarla o en su primer uso. Las operaciones se validan contra ella antes de generar SQL:

| Función | Versión mínima | Si el servidor es anterior |
|---|---|---|
| Identidad, `DEFAULT ON NULL`, columnas invisibles, `ROW STORE COMPRESS`, `DROP INDEX ONLINE` | 12.1 | — |
| Privilegio `READ`, `COMPRESS ADVANCED HIGH` | 12.1.0.2 | Se bloquea y se sugiere `SELECT` |
| Nombres de más de 30 bytes, particionado `LIST` por varias columnas | 12.2 | Se bloquea con el nombre que excede |
| `ALTER SEQUENCE ... RESTART` | 18c | Se bloquea (en 12c: eliminar y recrear) |
| Tipo `JSON` | 21c | Se bloquea y se sugiere `CLOB` + `CHECK (... IS JSON)` |
| Tipo `BOOLEAN` | 23ai | Se bloquea y se sugiere `NUMBER(1)`/`CHAR(1)` + `CHECK` |

  El editor tampoco ofrece tipos de dato que la versión no tiene.
- Probado en vivo contra **Oracle 23ai** (DEVPDB/PRODPDB) y **Oracle 18c XE**. Las reglas de 12.1 y 12.2 se verificaron con conexiones de versión simulada; no existe imagen gratuita de 12c para pruebas en vivo.
- Para repetir la prueba en 18c:

  ```bash
  docker compose --profile compat up -d oracle18
  ```

  Después activa la conexión "Oracle 18c QA" en Administración.
- **Cuentas de servicio en servidores reales:** `scripts/oracle/cuentas_servicio.sql` (como SYSDBA, en la PDB a administrar). Se adapta a la versión: en 12.1.0.1 usa `SELECT ANY TABLE` en lugar de `READ ANY TABLE`.
- El driver `python-oracledb` en modo *thin* soporta servidores 12.1+. Si una cuenta solo tiene verificador de contraseña 10G (muy antiguo), hay que restablecer su contraseña.

## Orden de ejecución

- Las operaciones se ejecutan **una por una en el orden mostrado** en la solicitud, y cada una sentencia por sentencia. Al primer error todo se detiene; nada posterior se ejecuta.
- El avance se guarda por operación y por sentencia. **El reintento continúa en la sentencia exacta donde falló**, sin repetir lo ya aplicado (el DDL de Oracle hace commit implícito).
- La solicitud se bloquea en estado `EXECUTING` durante la ejecución: si dos DBAs ejecutan a la vez, el segundo recibe un aviso. Si el servicio se reinicia a mitad, la solicitud pasa a `FAILED` y se puede reintentar.
- "Validar contra la base" detecta **dependencias mal ordenadas**: si una operación usa una tabla, columna, usuario, índice o secuencia que se crea en una operación posterior, indica cuál mover y antes de cuál.

## Flujo de una solicitud

`Borrador → Pendiente de aprobación → Aprobada → Ejecutada` (con *Cambios solicitados*, *Rechazada*, *Falló* → reintentar, *Cancelada*).

- Motivo (¿por qué?), finalidad (¿para qué?), proyecto y ticket quedan ligados a la solicitud.
- Quien solicita nunca aprueba lo suyo. Un `dba` necesita alcance sobre **todos** los esquemas afectados; un `dba_admin` aprueba todo. Las aprobaciones requeridas se configuran por conexión.
- El SQL se genera desde formularios con identificadores validados; lo que se aprueba es exactamente lo que se ejecuta. Editar la solicitud invalida las aprobaciones.
- **Importar SQL**: en el editor se puede pegar o subir un `.sql` (CREATE TABLE, GRANT/REVOKE, sinónimos, CREATE USER, ALTER TABLE ADD/MODIFY/DROP COLUMN, índices, procedimientos/vistas, DROP…). Se convierte en operaciones visuales editables; las FK, UNIQUE y CHECK se agregan como `ALTER TABLE` después de la tabla, y lo que no puede representarse sin cambiar su significado (tipos no soportados, particiones, nombres entre comillas con minúsculas, secuencias…) queda tal cual como SQL personalizado. Cada operación importada muestra notas de lo que se ajustó.
- "Validar contra la base" revisa el diccionario: objetos o usuarios inexistentes, FKs que impiden un `DROP`, sinónimos que quedarían inválidos, etc.
- Ejecutar exige teclear el código de la solicitud. Hay dos modos:
  - **Desde la app**, con la cuenta ejecutora, si la conexión lo permite. Se detiene en el primer error y reporta errores de compilación PL/SQL.
  - **Manual**: se descarga el `.sql`, se ejecuta fuera y se registra con notas.
- Las sesiones a Oracle llevan `client_identifier` = usuario de la app, para correlacionar con la auditoría de Oracle.
- `CREATE USER` genera la contraseña al ejecutar; se muestra una sola vez y no se guarda.

## Autenticación e identidad (Keycloak configurable)

OIDC estándar: Authorization Code + PKCE S256, con un cliente propio en `frontend/src/auth.ts`. Funciona también en HTTP por IP. El backend valida firma, issuer y audiencia con el JWKS. Todo se define en `.env`:

| Variable | Para qué |
|---|---|
| `COMPOSE_PROFILES=keycloak` | Levanta el Keycloak incluido. Quítalo para usar un Keycloak corporativo. |
| `KEYCLOAK_PUBLIC_URL` | URL con la que el **navegador** llega a Keycloak; arma el issuer. |
| `KEYCLOAK_INTERNAL_URL` | URL con la que el **backend** llega a Keycloak (red interna). Vacío = la pública. |
| `KEYCLOAK_REALM`, `KEYCLOAK_CLIENT_ID` | Realm y cliente público de inicio de sesión. |
| `OIDC_ISSUER_URL`, `OIDC_JWKS_URI`, `OIDC_AUDIENCE` | Opcionales para otros proveedores OIDC. `OIDC_AUDIENCE` vacío = no validar audiencia. |
| `KEYCLOAK_ROLES_SOURCE` (`realm` o `client`), `KEYCLOAK_ROLES_CLIENT` | De dónde salen los roles del token. |
| `ROLE_REQUESTER`, `ROLE_DBA`, `ROLE_DBA_ADMIN`, `ROLE_ADMIN` | Nombre en Keycloak de cada rol de la app (p. ej. grupos/roles corporativos). |
| `KEYCLOAK_ADMIN_CLIENT_ID`, `KEYCLOAK_ADMIN_CLIENT_SECRET` | Cuenta de servicio para asignar roles desde DBA Manager (opcional). |

- El realm incluido (`docker/keycloak/realm-dbamanager.json`) usa esas mismas variables (`${KEYCLOAK_REALM}`, etc.) al importarse por primera vez.
- El frontend lee URL, realm y cliente en tiempo de ejecución: no hay que reconstruirlo al cambiarlos.
- LDAP/Active Directory: configúralo como *User Federation* en Keycloak; los usuarios federados aparecen en DBA Manager.
- En producción usa HTTPS.

### Asignar roles desde DBA Manager

Administración → **Usuarios y roles** (solo rol `admin`):

- Lista los usuarios de Keycloak y permite marcar Solicitante / DBA / DBA principal / Admin.
- Los cambios se aplican en Keycloak y quedan en la bitácora (`ROLES_CHANGED`: quién, a quién, qué agregó o quitó).
- Toman efecto cuando el usuario renueva su sesión (minutos).
- Un admin no puede quitarse su propio rol de admin.
- Los roles heredados de grupos o roles compuestos se muestran, pero no se editan aquí.
- Si un usuario queda como DBA sin alcances de esquemas, se avisa.

Para habilitarlo en cualquier Keycloak (el incluido o uno corporativo):

```bash
scripts/keycloak/configurar_cliente_admin.sh
```

- El script crea o actualiza el cliente confidencial `KEYCLOAK_ADMIN_CLIENT_ID` con su secreto.
- Le da a su cuenta de servicio solo `view-users`, `query-users`, `manage-users` y `view-clients` de `realm-management`. No necesita `view-realm` ni `manage-realm`.
- Crea los 4 roles si no existen.
- Para un Keycloak externo, define `KC_SETUP_URL`, `KC_SETUP_USER` y `KC_SETUP_PASS` al ejecutarlo.

## Metadata: migraciones, respaldo y migración

- Esquema versionado con **Alembic** (`backend/migrations`). Se aplica solo al arrancar el backend.
  - Nueva versión: `alembic revision --autogenerate -m "..."`.
- **JSON portable**: Administración → Respaldo, o `GET /api/admin/backup` y `POST /api/admin/restore`. Solo restaura sobre metadata vacía, salvo `replace=true`. Las credenciales viajan cifradas, así que el destino necesita la misma `SECRETS_KEY`.
  - Para migrar a otra instancia o motor: `METADATA_URL=<url sqlalchemy>`, arrancar (Alembic crea las tablas) e importar el JSON.
- **Data Pump** del esquema `DBAMGR`:

  ```bash
  scripts/backup-metadata.sh                      # export a ./backups
  scripts/backup-metadata.sh restore archivo.dmp  # import
  ```

## Desarrollo

```bash
# backend con recarga (requiere Oracle y Keycloak arriba)
docker compose run --rm -p 8000:8000 -v $PWD/backend:/app backend uvicorn app.main:app --host 0.0.0.0 --reload
# frontend con Vite (http://localhost:5173, proxy /api → :8000)
cd frontend && npm install && npm run dev
```

Nota: si el umask del host es restrictivo, los archivos montados en contenedores deben ser legibles por "otros" (p. ej. `docker/oracle/init` → 755/644).
