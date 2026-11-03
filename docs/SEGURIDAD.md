# Seguridad de DBA Manager

DBA Manager puede aplicar DDL en bases productivas con una cuenta de privilegios amplios. Por eso, cada cambio pasa por varias barreras independientes, y todo queda en una bitácora.

Los controles de esta guía aplican a las dos versiones (FastAPI y Quarkus). La versión Quarkus agrega los de la sección [Versión Quarkus](#versión-quarkus).

## Controles

### Acceso

- **Solo con rol.** Iniciar sesión en Keycloak no basta: sin uno de los roles de DBA Manager, la API responde 403 a todo.
- **Tokens validados.** Se verifican la firma (llaves publicadas por Keycloak), el emisor, el vencimiento y la audiencia (`OIDC_AUDIENCE`). El inicio de sesión usa Authorization Code + PKCE.
- **Sin autoescalada.** Un admin no puede cambiar sus propios roles ni asignarse alcances de aprobación; eso lo hace otro admin.
- **Separación de funciones.** Quien solicita nunca aprueba su propia solicitud.

### Qué se puede pedir

- **Esquemas protegidos.** Ninguna solicitud puede tocar:
  - los esquemas internos de Oracle (lista fija + `ORACLE_MAINTAINED='Y'` del diccionario, revisado al validar y otra vez al ejecutar);
  - las cuentas de servicio de cada conexión;
  - el usuario de la metadata, para que nadie pueda, por ejemplo, borrar la bitácora con una solicitud aprobada;
  - los de `PROTECTED_SCHEMAS`.

  Tampoco se pueden otorgar permisos a esas cuentas, por ejemplo para que la cuenta de catálogo lea datos.
- **SQL generado, no escrito.** Los identificadores se validan de forma estricta y las expresiones libres (DEFAULT, CHECK, índices por función) no pueden cerrar la sentencia: no admiten `;`, comentarios, comas sueltas ni paréntesis desbalanceados.
- **Límites.** Hay un máximo de operaciones por solicitud (`MAX_OPERATIONS`) y de tamaño en textos, SQL y respaldos.

### Quién aprueba

- **Alcance completo.** Un DBA con alcance limitado solo aprueba si cubre **todos** los esquemas que la solicitud modifica **o referencia**: destino de un sinónimo, tabla de una llave foránea, etc.
- **Alto riesgo, solo DBA principal.** El SQL personalizado, el código PL/SQL (procedimientos, triggers, vistas…), la creación de usuarios y los sinónimos públicos solo los aprueba y ejecuta un `dba_admin`. En esas operaciones no se puede verificar automáticamente qué esquemas afectan.

### Ejecución

- **Lo aprobado es lo que se ejecuta.** Cada aprobación sella con HMAC (llave `SECRETS_KEY`) el SQL exacto y la base destino: host, servicio, cuenta ejecutora y TLS. Antes de ejecutar, o de marcar como ejecutada, se verifica el sello. Si alguien cambió la conexión o alteró el SQL en la metadata, la solicitud vuelve a "Pendiente de aprobación" y el intento queda en la bitácora (`INTEGRITY_MISMATCH`).
- **Ejecución desde la app, solo por decisión propia.** Para que un DBA pueda ejecutar desde la app:
  - está desactivada para todos por defecto;
  - solo el propio DBA la activa, y debe haber iniciado sesión hace menos de `REAUTH_MINUTES` (si no, Keycloak le vuelve a pedir la contraseña); en Quarkus la activa firmando con su llave;
  - **se desactiva sola** después de `SELF_EXECUTION_HOURS`;
  - nadie puede activarla por otro.
- **Confirmación y bloqueo.** Ejecutar exige teclear el código de la solicitud. La solicitud se bloquea mientras se ejecuta, y no se ejecuta en conexiones desactivadas.
- **Sin acceso a datos.** El explorador usa una cuenta que solo lee el diccionario (ver [ORACLE.md](ORACLE.md)).

### Integridad de la metadata y respaldos

- **Respaldos firmados.** Los respaldos van firmados con HMAC; uno editado a mano o sin firma no se restaura.
- **Sin reemplazo por defecto.** Reemplazar una metadata con datos, lo que borraría la bitácora, está deshabilitado (`METADATA_RESTORE_REPLACE=false`).
- **Reaprobación tras restaurar.** Al restaurar, la ejecución propia se desactiva para todos y lo aprobado sin ejecutar debe aprobarse de nuevo.
- **Contraseñas cifradas.** Las contraseñas de las conexiones se guardan cifradas (`SECRETS_KEY`) y nunca se devuelven por la API.
- **Contraseñas generadas.** Las de `CREATE USER` se muestran una sola vez y no se guardan.

### Aplicación web

- **Cabeceras de seguridad:**
  - CSP estricta: solo scripts propios, y conexiones solo a la API y a Keycloak;
  - prohibido incrustarla en iframes;
  - `nosniff`, `Referrer-Policy: no-referrer` y HSTS cuando llega por HTTPS;
  - `Cache-Control: no-store` en toda la API.
- **Documentación de la API** apagada por defecto (`API_DOCS_ENABLED`, versión FastAPI). La versión Quarkus no la incluye.
- **IP real en la bitácora:** solo se confía en `X-Forwarded-For` de los proxies de `FORWARDED_ALLOW_IPS`.
- **Imagen:** corre sin root.
- **Dependencias:** con versiones fijas y auditadas, sin vulnerabilidades conocidas a la fecha.

### Versión Quarkus

- **Sesión en el servidor.** El inicio de sesión (Authorization Code + PKCE) lo completa el servidor. El navegador solo guarda una cookie cifrada `HttpOnly`; nunca ve los tokens.
- **Protección contra CSRF.** Toda escritura exige la cabecera propia de la app y que el origen sea el de la app.
- **Firma de cada decisión.** Aprobar, rechazar, ejecutar, activar la ejecución propia, otorgar alcances y avalar llaves se firman con la llave de seguridad del DBA (WebAuthn / FIDO2). La llave privada nunca sale de su dispositivo.
  - La firma cubre la huella SHA-256 del SQL y de la base destino.
  - Antes de ejecutar se vuelven a verificar las firmas; si alguna no corresponde, la solicitud vuelve a "Pendiente de aprobación".
  - Un alcance o una llave sin firma válida de un DBA principal no cuenta.
- **Registro de llaves con contraseña reciente.** Keycloak vuelve a pedir la contraseña (`prompt=login`) antes de registrar una llave.
- **Imagen:** funciona con cualquier UID y con el sistema de archivos de solo lectura.

Detalles de la cadena de confianza y del restablecimiento de la llave raíz en [../quarkus/README.md](../quarkus/README.md).

### Al arrancar

La aplicación se niega a iniciar con una `SECRETS_KEY` inválida o con contraseñas de ejemplo (`change_me`). Avisa si Keycloak usa HTTP, si no se valida la audiencia o si alguna opción de seguridad está relajada.

## Lista de verificación para producción

- [ ] `SECRETS_KEY` generada de forma aleatoria, guardada como secreto y **respaldada**. Sin ella no se descifran las conexiones ni se restauran respaldos.
- [ ] Keycloak y DBA Manager **solo por HTTPS**.
- [ ] `OIDC_AUDIENCE` configurado, con el *audience mapper* de [KEYCLOAK.md](KEYCLOAK.md).
- [ ] En el cliente de Keycloak:
  - *Direct access grants* desactivado;
  - *Valid redirect URIs* y *Web origins* con la URL exacta, sin `*`.
- [ ] En el realm:
  - *Brute force detection* activado (Realm settings → Security defenses);
  - sesiones con duración razonable (Realm settings → Sessions).
- [ ] `API_DOCS_ENABLED=false` (FastAPI), `METADATA_RESTORE_REPLACE=false`, `SELF_EXECUTION_HOURS` > 0.
- [ ] Quarkus: `APP_PUBLIC_URL` con la dirección HTTPS definitiva, y cada DBA con **dos llaves** registradas.
- [ ] `FORWARDED_ALLOW_IPS` con la IP del balanceador o router, no `*`, salvo que solo se llegue a la app a través de él.
- [ ] Cuenta ejecutora solo con los privilegios de las operaciones que se permitan ([ORACLE.md](ORACLE.md) §2.2), o sin cuenta ejecutora si no se ejecutará desde la app.
- [ ] Conexiones a Oracle cifradas: *Conexión cifrada (TLS)* en cada conexión y `tcps://` en `METADATA_DSN`, si el listener lo permite.
- [ ] Usuario de la metadata solo con `CREATE SESSION` después de instalar ([ORACLE.md](ORACLE.md) §1).
- [ ] Pocos usuarios con `admin` y `dba_admin`; los DBAs de equipo con `dba` y alcances acotados.

## Riesgos que permanecen por diseño

| Riesgo | Mitigación |
|---|---|
| Todos los usuarios con rol ven la estructura de todos los esquemas, incluido el **código fuente** de procedimientos y vistas. Si alguien guardó credenciales dentro de un paquete, son visibles. | Es una decisión del producto: todos ven toda la estructura. Revisar que el código no contenga credenciales. |
| El texto de las solicitudes (incluido el SQL personalizado) es visible para todos los usuarios con rol. | No escribir contraseñas en solicitudes. Los usuarios nuevos reciben contraseña generada. |
| La cuenta ejecutora tiene privilegios `ANY`. | Otorgar solo los necesarios, o no crearla y ejecutar manualmente. Solo se usa con aprobación sellada y ejecución propia activa. |
| Un `admin` puede dar de alta y modificar conexiones. | Si cambia la base destino, lo aprobado deja de valer. Todo cambio queda en la bitácora. |
| Quien tenga acceso de escritura directo al esquema de la metadata, y además la `SECRETS_KEY`, podría falsificar sellos. | Proteger la `SECRETS_KEY` como secreto, y el usuario de metadata solo con `CREATE SESSION`. En Quarkus, además, una aprobación sin la firma de la llave del DBA no se ejecuta. |
| FastAPI: la sesión del navegador guarda el token en `sessionStorage`. | Mitigado con la CSP estricta (impide ejecutar scripts ajenos) y tokens de vida corta de Keycloak. En Quarkus no aplica: la sesión es una cookie cifrada del servidor. |
