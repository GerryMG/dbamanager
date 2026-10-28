# Qué debe existir en Oracle para DBA Manager

DBA Manager trabaja con dos tipos de bases Oracle (12.1 o superior):

| Base | Para qué | Cuentas |
|---|---|---|
| **Metadata** | Guarda solicitudes, aprobaciones, comentarios, bitácora, conexiones y configuración | 1 usuario dueño (p. ej. `DBAMGR`) |
| **Bases administradas** | Las que se exploran y a las que se aplican los cambios aprobados | 1 cuenta de lectura del diccionario y, opcionalmente, 1 cuenta ejecutora |

Qué ejecuta DBA Manager en cada una:

- **En la metadata:** solo trabaja con sus propias tablas, dentro de su usuario. Las crea al instalarse y las actualiza al cambiar de versión.
- **En las bases administradas:** solo **lee el diccionario de datos** (estructura, nunca datos), y aplica cambios **únicamente** cuando un DBA ejecuta una solicitud aprobada con la ejecución activada. Fuera de eso no ejecuta nada.

---

## 1. Base de metadata

Un usuario dedicado, por ejemplo `DBAMGR`, en el Oracle y PDB que elijan.

| Elemento | Valor |
|---|---|
| Usuario | El nombre que prefieran → `METADATA_USER` (por defecto `DBAMGR`) |
| Tablespace por defecto | El que prefieran, con **cuota** suficiente (unos cientos de MB alcanzan para años de bitácora) |
| Conexión | `host:puerto/servicio` → `METADATA_DSN`; contraseña → `DBAMGR_PASSWORD` |

**Privilegios de sistema:**

| Momento | Privilegios | Por qué |
|---|---|---|
| Instalación y cada actualización de versión | `CREATE SESSION`, `CREATE TABLE`, `CREATE SEQUENCE` | Al arrancar, DBA Manager crea o actualiza sus tablas. `CREATE SEQUENCE` es necesario porque las llaves son columnas de identidad. |
| Operación normal | `CREATE SESSION` | Leer y escribir en sus propias tablas no requiere más. |

- No necesita privilegios `ANY`, roles, `SELECT_CATALOG_ROLE` ni acceso a otros esquemas.
- Puede retirarse `CREATE TABLE` y `CREATE SEQUENCE` después de instalar, y volver a darlos solo para actualizar a una versión nueva de DBA Manager. Si en esa versión no hay cambios de tablas, el arranque solo lee la versión instalada y no los necesita.
- Si al arrancar faltan privilegios o la base no responde, DBA Manager no arranca y lo indica en el log con `[ERROR]`.

> Probado: con solo `CREATE TABLE` la instalación falla (`ORA-01031`). Con `CREATE SEQUENCE` agregado instala bien, y después funciona completa (solicitudes, aprobaciones, bitácora, respaldo y restauración) con solo `CREATE SESSION`.

---

## 2. Bases administradas

En cada base o PDB que se quiera administrar se crean dos cuentas. Los nombres son libres y se indican al dar de alta la conexión en *Administración → Conexiones*.

### 2.1 Cuenta de lectura del diccionario (obligatoria)

Se usa para explorar la estructura, validar las solicitudes antes de aprobarlas, importar scripts y detectar la versión del servidor.

| Elemento | Valor |
|---|---|
| Nombre sugerido | `DBAMGR_CATALOG` |
| Privilegios | `CREATE SESSION` y el rol `SELECT_CATALOG_ROLE` |
| Tablespace / cuota | No necesita |

**Qué hace con ella DBA Manager:**

- Solo consultas (`SELECT`) a vistas del diccionario.
- Lee la definición de objetos con `DBMS_METADATA`, que también es lectura.
- Nunca consulta tablas de los esquemas, y no podría: no tiene `SELECT ANY TABLE` ni permisos sobre ellas.

Vistas que consulta:

`DBA_USERS`, `DBA_ROLES`, `DBA_OBJECTS`, `DBA_TABLES`, `DBA_TAB_COLUMNS`, `DBA_TAB_COLS`, `DBA_TAB_COMMENTS`, `DBA_COL_COMMENTS`, `DBA_TAB_IDENTITY_COLS`, `DBA_CONSTRAINTS`, `DBA_CONS_COLUMNS`, `DBA_INDEXES`, `DBA_IND_COLUMNS`, `DBA_IND_EXPRESSIONS`, `DBA_INDEXTYPES`, `DBA_PART_TABLES`, `DBA_PART_INDEXES`, `DBA_PART_KEY_COLUMNS`, `DBA_TAB_PARTITIONS`, `DBA_TAB_PRIVS`, `DBA_COL_PRIVS`, `DBA_SYS_PRIVS`, `DBA_ROLE_PRIVS`, `DBA_TS_QUOTAS`, `DBA_SYNONYMS`, `DBA_TRIGGERS`, `DBA_DEPENDENCIES`, `DBA_MVIEWS`, `DBA_MVIEW_LOGS`, `DBA_ERRORS`, `V$VERSION`, `SESSION_PRIVS` y el paquete `DBMS_METADATA`.

> `SELECT_CATALOG_ROLE` es necesario porque `DBMS_METADATA` solo devuelve la definición de objetos de otros esquemas a quien tiene ese rol. Otorgar `SELECT` vista por vista no es suficiente para ver el DDL.

### 2.2 Cuenta ejecutora (opcional)

Solo se usa cuando un DBA ejecuta **desde la app** una solicitud aprobada, y solo si se cumplen las tres condiciones:

1. La conexión tiene marcada *Permitir ejecución desde DBA Manager*.
2. Ese DBA activó para sí mismo la ejecución desde la app.
3. Escribió el código de la solicitud para confirmar.

Ejecuta exactamente el SQL que se aprobó, en orden, y nada más. **Si no se quiere ejecutar desde la app, no se crea esta cuenta**: los DBAs descargan el script, lo ejecutan por su cuenta y lo registran como ejecución manual.

| Elemento | Valor |
|---|---|
| Nombre sugerido | `DBAMGR_EXEC` |
| Privilegios base | `CREATE SESSION` |
| Opcional | `SELECT_CATALOG_ROLE`: solo para mostrar el detalle línea por línea cuando un procedimiento compila con errores. Sin él, igual se informa que hubo errores. |
| Tablespace / cuota | No necesita (los objetos se crean en el esquema dueño) |

**Privilegios según las operaciones que se quieran permitir.** Otorguen solo los de las operaciones que vayan a ejecutar desde la app:

| Operación | Privilegios de la cuenta ejecutora |
|---|---|
| Crear tabla | `CREATE ANY TABLE`; con columna de identidad, además `SELECT ANY SEQUENCE` |
| Comentarios de tabla o columna | `COMMENT ANY TABLE` |
| Agregar, modificar, renombrar o eliminar columnas; restricciones; renombrar tabla; logging, compresión o solo lectura; agregar o eliminar particiones | `ALTER ANY TABLE` |
| Eliminar tabla | `DROP ANY TABLE` |
| Crear índice | `CREATE ANY INDEX` |
| Reconstruir, cambiar visibilidad o renombrar índice | `ALTER ANY INDEX` |
| Eliminar índice | `DROP ANY INDEX` |
| Otorgar o revocar permisos sobre objetos | `GRANT ANY OBJECT PRIVILEGE` |
| Crear o eliminar sinónimos | `CREATE ANY SYNONYM`, `DROP ANY SYNONYM`; los públicos: `CREATE PUBLIC SYNONYM`, `DROP PUBLIC SYNONYM` |
| Crear usuarios | `CREATE USER`; para darles privilegios de sistema, `GRANT ANY PRIVILEGE`; para darles roles, `GRANT ANY ROLE` |
| Secuencias | `CREATE ANY SEQUENCE`, `ALTER ANY SEQUENCE`, `DROP ANY SEQUENCE` |
| Procedimientos, funciones y paquetes | `CREATE ANY PROCEDURE`, `ALTER ANY PROCEDURE`, `DROP ANY PROCEDURE` |
| Tipos | `CREATE ANY TYPE`, `DROP ANY TYPE` |
| Vistas | `CREATE ANY VIEW`, `DROP ANY VIEW` |
| Triggers | `CREATE ANY TRIGGER`, `ALTER ANY TRIGGER`, `DROP ANY TRIGGER` |
| Vistas materializadas | `CREATE ANY MATERIALIZED VIEW`, `ALTER ANY MATERIALIZED VIEW`, `DROP ANY MATERIALIZED VIEW` y `READ ANY TABLE` (Oracle exige leer la tabla base aunque esté en otro esquema; en 12.1.0.1, `SELECT ANY TABLE`) |
| Logs de vista materializada | `CREATE ANY TABLE`, `DROP ANY TABLE` y `READ ANY TABLE` |
| SQL personalizado | Lo que requiera ese SQL |

Si falta un privilegio, la ejecución se detiene en esa operación y muestra el error de Oracle (por ejemplo `ORA-01031` u `ORA-41900`). Al otorgarlo, el reintento continúa desde esa misma sentencia, sin repetir lo ya aplicado.

> *Administración → Conexiones → Probar (Ejecutora)* muestra los privilegios que tiene realmente la cuenta.

### 2.3 Requisitos de los esquemas dueños

Son las reglas normales de Oracle, no algo propio de DBA Manager. La validación previa avisa de muchas de ellas.

| Para | El esquema dueño necesita |
|---|---|
| Que se creen tablas o índices en él | Cuota en su tablespace |
| Ser dueño de una vista materializada | `CREATE TABLE` (o `CREATE MATERIALIZED VIEW`) |
| Una llave foránea hacia otro esquema | `REFERENCES` sobre la tabla referenciada |
| Una vista o un procedimiento que lean tablas de otro esquema | Permisos directos (no por rol) sobre esas tablas |

---

## 3. Red

| Desde | Hacia |
|---|---|
| DBA Manager | El listener de la base de metadata (`METADATA_DSN`) |
| DBA Manager | El listener de cada base administrada (host/puerto/servicio de cada conexión) |

El driver se conecta directamente al listener (no requiere cliente de Oracle instalado) y es compatible con Oracle 12.1 o superior. Las cuentas deben tener una contraseña con verificador 11G o superior; las cuentas muy antiguas con verificador 10G deben cambiar su contraseña.
