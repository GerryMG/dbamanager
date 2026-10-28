# DBA Manager

Explora la **estructura** de bases de datos Oracle (nunca los datos) y gestiona solicitudes de cambio con **aprobación de DBA**:
- tablas, columnas, restricciones e índices;
- particiones, permisos, sinónimos y usuarios lectores;
- secuencias y código (procedimientos, vistas, triggers…).

Cada solicitud registra el motivo, el proyecto y la finalidad, y queda en una bitácora completa de quién pidió, aprobó y ejecutó.

## Arquitectura

Es **una sola aplicación**: el backend (FastAPI) sirve también la interfaz web (React) en el mismo puerto. Todo lo demás es externo y se configura con variables:

| Componente | Descripción |
|---|---|
| DBA Manager | API `/api` (documentación en `/api/docs`) + interfaz web en `/`, puerto `PORT` |
| Metadata | Oracle 12.1+ externo (`METADATA_DSN`), usuario **`DBAMGR`** con privilegios mínimos |
| Identidad | Keycloak / OIDC externo (`KEYCLOAK_*`) |
| Bases administradas | Oracle 12.1+, una conexión por base (host/puerto/servicio), dadas de alta en la app |

```
backend/    API, generación de SQL, flujo de aprobación (Python)
frontend/   interfaz web (React + Mantine); se compila y la sirve el backend
scripts/    arranque local (Linux/macOS y Windows)
docs/       qué debe existir en Oracle y en Keycloak
demo/       infraestructura opcional de prueba (Oracle y Keycloak en Docker)
```

## Preparar los servidores

Qué debe existir antes de arrancar:

1. **Oracle:** sigue [docs/ORACLE.md](docs/ORACLE.md).
   - **Metadata:** un usuario dedicado; DBA Manager crea ahí sus tablas.
   - **Cada base a administrar:** una cuenta de solo lectura del diccionario y, si se quiere ejecutar desde la app, una cuenta ejecutora con los privilegios de las operaciones que se permitan.
2. **Keycloak:** sigue [docs/KEYCLOAK.md](docs/KEYCLOAK.md). Basta un usuario administrador de tu realm; no hace falta `master`.

## Configuración

Copia `.env.example` a `.env` y completa los valores. **Todas** las variables están ahí, agrupadas y explicadas. Las variables de entorno del sistema tienen prioridad sobre `.env`.

Al arrancar, la app verifica la configuración y muestra en el log una línea por chequeo:
- `[OK]`: conexión a la metadata, issuer y llaves de Keycloak correctos.
- `[AVISO]`: algo no cuadra (por ejemplo, una URL de Keycloak que no coincide con el issuer real).
- `[ERROR]`: falta la metadata o una variable obligatoria; la app no arranca.

## Correr en cualquier máquina (sin Docker)

Requisitos: **Python 3.11+** y **Node.js 18+**. Los servidores de metadata, Keycloak y bases deben ser accesibles desde esa máquina.

Linux / macOS:

```bash
cp .env.example .env
```

```bash
scripts/iniciar-local.sh
```

Windows (PowerShell):

```powershell
Copy-Item .env.example .env
```

```powershell
.\scripts\iniciar-local.ps1
```

- La primera vez el script crea el entorno de Python, instala dependencias y compila la interfaz. Después solo arranca.
- Si cambias código del frontend, agrega `--build` (o `-Build` en Windows) para recompilarlo.
- Abre `http://localhost:<PORT>`. En Keycloak, esa URL debe estar en *Valid redirect URIs* y *Web origins* del cliente.

## Roles y permisos

| Rol (en Keycloak) | Puede |
|---|---|
| `solicitante` | Explorar estructura y crear solicitudes |
| `dba` | Aprobar/ejecutar solo en los esquemas que se le asignen (Administración → Alcances de DBAs) |
| `dba_admin` | Aprobar/ejecutar cualquier solicitud |
| `admin` | Conexiones, usuarios y roles, alcances, proyectos, respaldos |

- Quien solicita nunca aprueba lo suyo.
- En **Administración → Usuarios y roles** el admin asigna los roles con su propio usuario de Keycloak (`KEYCLOAK_ROLES_ADMIN_MODE=token`). Ver el paso 6 de [docs/KEYCLOAK.md](docs/KEYCLOAK.md).

### Permiso de ejecutar

Ejecutar desde la app (con la cuenta privilegiada) viene **desactivado para todos**:

- **Solo el propio DBA puede activarlo para sí mismo**, desde el menú de su usuario → *Ejecución desde la app*, escribiendo `ACTIVAR`.
- No existe forma de que otra persona lo active por él, ni siquiera un admin.
- Al restaurar un respaldo de metadata, vuelve a quedar desactivado para todos.
- Cada activación o desactivación queda en la bitácora.
- Además, la conexión debe permitir ejecución (Administración → Conexiones).
- Sin el permiso activado, el DBA descarga el script, lo ejecuta por su cuenta y lo registra como ejecución manual.

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

## Flujo de una solicitud

`Borrador → Pendiente de aprobación → Aprobada → Ejecutada` (con *Cambios solicitados*, *Rechazada*, *Falló* → reintentar, *Cancelada*).

- Motivo (¿por qué?), finalidad (¿para qué?), proyecto y ticket quedan ligados a la solicitud.
- Quien solicita nunca aprueba lo suyo. Un `dba` necesita alcance sobre **todos** los esquemas afectados; un `dba_admin` aprueba todo. Las aprobaciones requeridas se configuran por conexión.
- El SQL se genera desde formularios con identificadores validados; lo que se aprueba es exactamente lo que se ejecuta. Editar la solicitud invalida las aprobaciones.
- **Importar SQL**: en el editor se puede pegar o subir un `.sql` (CREATE TABLE, GRANT/REVOKE, sinónimos, CREATE USER, ALTER TABLE ADD/MODIFY/DROP COLUMN, índices, procedimientos/vistas, DROP…). Se convierte en operaciones visuales editables; las FK, UNIQUE y CHECK se agregan como `ALTER TABLE` después de la tabla, y lo que no puede representarse sin cambiar su significado (tipos no soportados, particiones, nombres entre comillas con minúsculas, secuencias…) queda tal cual como SQL personalizado. Cada operación importada muestra notas de lo que se ajustó.
- "Validar contra la base" revisa el diccionario: objetos o usuarios inexistentes, FKs que impiden un `DROP`, sinónimos que quedarían inválidos, etc.
- Ejecutar exige teclear el código de la solicitud. Hay dos modos:
  - **Desde la app**, con la cuenta ejecutora: solo si la conexión lo permite **y** el DBA activó para sí mismo la ejecución (ver "Permiso de ejecutar"). Se detiene en el primer error y reporta errores de compilación PL/SQL.
  - **Manual**: se descarga el `.sql`, se ejecuta fuera y se registra con notas.
- Las sesiones a Oracle llevan `client_identifier` = usuario de la app, para correlacionar con la auditoría de Oracle.
- `CREATE USER` genera la contraseña al ejecutar; se muestra una sola vez y no se guarda.

## Orden de ejecución

- Las operaciones se ejecutan **una por una en el orden mostrado** en la solicitud, y cada una sentencia por sentencia. Al primer error todo se detiene; nada posterior se ejecuta.
- El avance se guarda por operación y por sentencia. **El reintento continúa en la sentencia exacta donde falló**, sin repetir lo ya aplicado (el DDL de Oracle hace commit implícito).
- La solicitud se bloquea en estado `EXECUTING` durante la ejecución: si dos DBAs ejecutan a la vez, el segundo recibe un aviso. Si el servicio se reinicia a mitad, la solicitud pasa a `FAILED` y se puede reintentar.
- "Validar contra la base" detecta **dependencias mal ordenadas**: si una operación usa una tabla, columna, usuario, índice o secuencia que se crea en una operación posterior, indica cuál mover y antes de cuál.

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
- Para repetir la prueba en 18c: `docker compose -f demo/docker-compose.yml --env-file .env --profile compat up -d oracle18`.
- En 12.1.0.1 no existe `READ ANY TABLE`: donde la guía lo pide, se usa `SELECT ANY TABLE` (ver [docs/ORACLE.md](docs/ORACLE.md)).
- El driver `python-oracledb` en modo *thin* soporta servidores 12.1+. Si una cuenta solo tiene verificador de contraseña 10G (muy antiguo), hay que restablecer su contraseña.

## Metadata: migraciones y respaldo

- El esquema de la metadata se versiona con **Alembic** (`backend/migrations`) y se actualiza solo al arrancar.
- **Respaldo y migración:** Administración → *Respaldo de metadata* descarga o restaura un JSON portable con toda la metadata.
  - Las contraseñas de conexiones van cifradas: para restaurar se necesita la misma `SECRETS_KEY`.
  - Solo restaura sobre metadata vacía, salvo que se active "reemplazar".
- También puedes respaldar el esquema `DBAMGR` con las herramientas habituales de Oracle (Data Pump).

## Desarrollo

Con recarga automática, en dos terminales. Primero el backend (puerto 8080):

```bash
cd backend && .venv/bin/uvicorn app.main:app --reload --port 8080
```

Luego el frontend (puerto 5173; `/api` se redirige al backend):

```bash
cd frontend && npm run dev
```

Agrega `http://localhost:5173` en `CORS_ORIGINS` y en las URLs del cliente de Keycloak.

## Infraestructura de demostración (opcional)

`demo/docker-compose.yml` levanta un Oracle 23ai (metadata y bases de ejemplo DEVPDB/PRODPDB) y un Keycloak con usuarios de prueba. Sirve para probar sin servidores propios:

```bash
docker compose -f demo/docker-compose.yml --env-file .env up -d
```

En `.env` usa:
- `METADATA_DSN=localhost:1521/FREEPDB1`
- `KEYCLOAK_PUBLIC_URL=http://<ip>:8180`
- `SEED_DEMO=true`
- las contraseñas de la sección "Solo para demo".

Usuarios demo (contraseña `Cambiar.123`):

| Usuario | Roles |
|---|---|
| `admin.dba` | dba_admin, admin (con permisos para asignar roles en su realm) |
| `luis.dba` | dba (alcance en `VENTAS` y `BI_LECTOR`) |
| `ana.dev`, `pedro.bi` | solicitante |
