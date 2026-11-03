# DBA Manager — versión Quarkus

La misma aplicación de DBA Manager, hecha completa en **Quarkus**. La comparación con la versión FastAPI está en el [README principal](../README.md#dos-versiones). Un solo proyecto Java sirve la API y la interfaz web en el mismo puerto. La interfaz es JavaScript estándar servido por Quarkus, así que no hace falta Node ni ningún paso de compilación aparte.

- **Exploración** de estructura de Oracle, solo con el diccionario de datos (nunca datos).
- **Solicitudes** de cambios con las 28 operaciones visuales, importación de scripts SQL, vista previa del SQL y validación previa contra la base.
- **Aprobación de DBAs** con alcances por esquema y bitácora completa.
- **Firma de cada decisión** con la llave de seguridad del DBA.

## Arquitectura

| Pieza | Tecnología |
|---|---|
| API REST | Quarkus REST + Jackson |
| Inicio de sesión | Quarkus OIDC (Keycloak u otro proveedor OIDC) |
| Sesión | Cookie cifrada del servidor (Authorization Code + PKCE). El navegador nunca ve los tokens. |
| Metadata | Oracle 12.1+ con Hibernate ORM; las tablas se crean y actualizan con Flyway |
| Bases administradas | JDBC thin de Oracle |
| Llaves de los DBAs | WebAuthn / FIDO2 (webauthn4j) |
| Interfaz | HTML + JavaScript (módulos ES) servidos por Quarkus, sin dependencias externas |

## Llave de seguridad del DBA

Cada DBA registra una **llave de seguridad**: una llave USB (YubiKey u otra FIDO2), la huella o el rostro de su equipo (Windows Hello, Touch ID) o su teléfono. La llave privada nunca sale de su dispositivo; DBA Manager solo guarda la llave pública.

**Qué se firma.** Todo lo que da o ejerce poder de decisión lleva la firma del DBA:

- aprobar, rechazar o pedir cambios a una solicitud;
- ejecutar una solicitud, desde la app o registrando la ejecución manual;
- activar su propia ejecución desde la app;
- otorgar alcances de aprobación a otros DBAs;
- avalar o revocar llaves;
- cancelar un restablecimiento de la llave raíz.

Antes de firmar, la pantalla muestra exactamente qué se firma. Eso incluye la **huella SHA-256 del SQL y de la base destino**.

**Verificación antes de ejecutar.** Se vuelven a verificar todas las firmas de las aprobaciones contra el SQL y la base actuales. Una aprobación sin firma válida no cuenta y la solicitud no se ejecuta. Lo mismo pasa con una firma hecha sobre otro SQL o con una llave que no es del aprobador. En esos casos la solicitud vuelve a "Pendiente de aprobación" y queda en la bitácora.

**Cadena de confianza:**

1. La primera llave que registra un **DBA principal** (rol `dba_admin`) es la **llave raíz**.
2. Cualquier otra llave queda pendiente hasta que un DBA principal la avala con su llave. Esto incluye las de los DBAs con rol `dba` y las de otros `dba_admin`.
3. Si es una segunda llave del mismo DBA, la avala él mismo con la primera.
4. Una llave sin aval válido hasta la raíz no puede decidir nada.
5. Un alcance de aprobación sin firma de un DBA principal no cuenta. Tener el rol `dba` en Keycloak no basta para aprobar.

**Registro.** Registrar una llave exige volver a escribir la contraseña. La app redirige a Keycloak con `prompt=login`, y hay `REAUTH_MINUTES` para completar el registro.

**Si el DBA principal pierde todas sus llaves.** Un `admin` o un `dba_admin` puede pedir el **restablecimiento de la raíz**:

- Espera **72 horas** (valor fijo).
- Se anuncia a todos los DBAs en un aviso visible en toda la app.
- Cualquier DBA principal con su llave puede cancelarlo.
- Si se aplica, se revocan todas las llaves, se desactiva la ejecución de todos y el siguiente DBA principal que registre su llave será la nueva raíz.

Se recomienda que cada DBA registre **dos llaves**, por ejemplo la del equipo y una USB.

> Las llaves de seguridad solo funcionan en **HTTPS** (o en `http://localhost` para pruebas). Define `APP_PUBLIC_URL` con la dirección definitiva: las llaves quedan ligadas a ese dominio.

## Preparar los servidores

- **Oracle:** usuario de metadata, cuenta de catálogo y cuenta ejecutora, con los privilegios de [../docs/ORACLE.md](../docs/ORACLE.md).
  - Usa un **usuario de metadata distinto** al de la versión FastAPI: las tablas tienen los mismos nombres.
  - La tabla de versión de esta app es `DBAMGR_SCHEMA_HISTORY`.
- **Keycloak:** igual que en [../docs/KEYCLOAK.md](../docs/KEYCLOAK.md).
  - Cliente con *Standard flow* y PKCE `S256`.
  - *Valid redirect URIs* con la dirección de la app seguida de `/*`. Así cubre también `/reauth`, la ruta donde se vuelve a pedir la contraseña.
  - Si el cliente es confidencial, pon su secreto en `KEYCLOAK_CLIENT_SECRET`.
- **Seguridad:** controles y lista de verificación en [../docs/SEGURIDAD.md](../docs/SEGURIDAD.md). Aplican igual, más la firma con llave descrita arriba.

## Configuración

Todas las variables están documentadas en [.env.example](.env.example). Cópialo a `.env` en la carpeta desde donde ejecutes la app; las variables de entorno del sistema tienen prioridad sobre `.env`.

Mínimo indispensable:

- `SECRETS_KEY`: genérala con `openssl rand -base64 32 | tr '+/' '-_'`.
- Metadata: `METADATA_DSN`, `METADATA_USER` y `DBAMGR_PASSWORD`.
- Keycloak: `KEYCLOAK_PUBLIC_URL`, `KEYCLOAK_REALM` y `KEYCLOAK_CLIENT_ID`.
- `APP_PUBLIC_URL`.

Al arrancar, el log muestra `[OK]`, `[AVISO]` o `[ERROR]`. La app no arranca con una `SECRETS_KEY` inválida, con contraseñas de ejemplo ni sin acceso a la metadata.

## Correr en cualquier máquina (sin Docker)

Requisitos: **JDK 21** y **Maven 3.9+**.

```bash
mvn package
```

```bash
java -jar target/quarkus-app/quarkus-run.jar
```

Ejecuta el segundo comando desde la carpeta donde está tu `.env`. La app queda en `http://localhost:8080`, o en el puerto de `PORT`.

Para desarrollo con recarga automática:

```bash
mvn quarkus:dev
```

## Imagen

El [Dockerfile](Dockerfile) compila con Maven y deja una imagen con solo el runtime de Java 21:

- corre sin root y funciona con cualquier UID;
- toma toda la configuración de variables de entorno;
- expone `/q/health/live` y `/q/health/ready`.

```bash
docker build -t dbamanager .
```

## Roles y permisos

| Rol | Puede |
|---|---|
| `solicitante` | Explorar la estructura y crear solicitudes |
| `dba` | Con su llave avalada, aprobar y ejecutar solo en los esquemas que un DBA principal le haya otorgado |
| `dba_admin` | DBA principal. Con su llave: aprobar y ejecutar todo, avalar llaves y otorgar alcances |
| `admin` | Conexiones, proyectos, roles en Keycloak y respaldos |

Las reglas de la versión FastAPI se mantienen:

- quien solicita no aprueba lo suyo;
- SQL libre, código PL/SQL, creación de usuarios y sinónimos públicos solo los aprueba un `dba_admin`;
- nunca se aceptan cambios en esquemas internos de Oracle, en las cuentas de servicio ni en la metadata;
- la ejecución desde la app la activa cada DBA para sí mismo, ahora con su llave, y se desactiva sola;
- ejecutar exige teclear el código de la solicitud y se hace en orden, retomando donde se quedó si algo falla.

## Compatibilidad

Funciona con Oracle 12.1 o superior. La sintaxis se ajusta a la versión de cada base:

| Función | Desde |
|---|---|
| Columnas identidad | 12.1 |
| Nombres de más de 30 bytes y LIST de varias columnas | 12.2 |
| `ALTER SEQUENCE … RESTART` | 18c |
| Tipo `JSON` | 21c |
| Tipo `BOOLEAN` | 23ai |

Probado contra Oracle 18c XE y Oracle 23ai Free.

## Respaldo de la metadata

*Administración → Respaldo* descarga un JSON firmado con toda la metadata, incluidas las llaves públicas y las firmas. Las firmas se pueden volver a verificar en el destino.

Al restaurar:

- se necesita la misma `SECRETS_KEY`;
- solo se restaura sobre una metadata vacía, salvo que `METADATA_RESTORE_REPLACE=true`;
- lo aprobado sin ejecutar vuelve a "Pendiente de aprobación".
