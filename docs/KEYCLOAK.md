# Configurar Keycloak para DBA Manager

Esta guía usa solo la consola de **tu realm**: no necesitas el usuario `master`.

## Qué necesitas

Un usuario de tu realm con estos roles del cliente **`realm-management`** (los da quien administra tu realm):

| Para | Rol de `realm-management` |
|---|---|
| Crear el cliente de inicio de sesión (pasos 1–3) | `manage-clients` |
| Crear roles de realm (paso 4, opción A) | `manage-realm` (si no lo tienes, usa la opción B) |
| Asignar roles a usuarios (paso 5) | `manage-users` |

Entra a la consola de tu realm, no a la de master:

```
https://<tu-keycloak>/admin/<tu-realm>/console/
```

---

## Paso 1. Crear el cliente con el que inician sesión los usuarios

1. **Clients → Create client**.
2. *Client type*: `OpenID Connect`. *Client ID*: `dbamanager-web`. Esto va en `KEYCLOAK_CLIENT_ID`. Clic en **Next**.
3. *Client authentication*: **Off**. Es un cliente público, sin secreto.
   - *Authentication flow*: marca solo **Standard flow**.
   - Desmarca *Direct access grants*.
   - Clic en **Next**.
4. Login settings (sustituye por la URL real de DBA Manager):
   - *Root URL*: `https://dbamanager.empresa.com`
   - *Valid redirect URIs*: `https://dbamanager.empresa.com/*`
   - *Valid post logout redirect URIs*: `https://dbamanager.empresa.com/*`
   - *Web origins*: `https://dbamanager.empresa.com`
5. **Save**.

> Para probar en tu máquina, agrega también `http://localhost:8080/*` en las URIs y `http://localhost:8080` en *Web origins*. Usa el puerto definido en `PORT`.

## Paso 2. Activar PKCE y endurecer el realm

En el cliente `dbamanager-web` → pestaña **Advanced** → sección *Advanced settings* → **Proof Key for Code Exchange Code Challenge Method**: `S256`. Clic en **Save**.

Recomendado en el realm (si tienes permiso, o pídeselo a quien lo administra):

- **Realm settings → Security defenses → Brute force detection**: activado, para bloquear intentos repetidos de contraseña.
- **Realm settings → Sessions**: una duración razonable de sesión (por ejemplo, 8 horas máximo y 30 minutos de inactividad).
- **Realm settings → Tokens → Access Token Lifespan**: corto (por ejemplo, 5 minutos). La app lo renueva sola.

## Paso 3. Hacer que el token sea "para" DBA Manager (audience)

Así DBA Manager solo acepta tokens emitidos para este cliente.

1. En el cliente `dbamanager-web` → pestaña **Client scopes** → clic en **`dbamanager-web-dedicated`**.
2. **Configure a new mapper** (o *Add mapper → By configuration*) → **Audience**.
3. *Name*: `audience-dbamanager`. *Included Client Audience*: `dbamanager-web`. *Add to access token*: **On**. Clic en **Save**.

En `.env`: `OIDC_AUDIENCE=dbamanager-web`.

> Si no puedes crear el mapper, deja `OIDC_AUDIENCE=` vacío. Funciona igual, pero sin esta validación extra.

## Paso 4. Crear los 4 roles de la app

Elige **una** opción e indícala en `KEYCLOAK_ROLES_SOURCE`.

**Opción A — roles del realm** (`KEYCLOAK_ROLES_SOURCE=realm`, requiere `manage-realm`):
**Realm roles → Create role**, cuatro veces: `solicitante`, `dba`, `dba_admin`, `admin`.

**Opción B — roles del cliente** (`KEYCLOAK_ROLES_SOURCE=client`, solo requiere `manage-clients`):
**Clients → dbamanager-web → Roles → Create role**, cuatro veces, con los mismos nombres. Deja `KEYCLOAK_ROLES_CLIENT` vacío, o con el nombre del cliente si los roles están en otro.

Si en tu organización ya existen roles o grupos equivalentes con otros nombres, no crees nada. Indica esos nombres en `ROLE_REQUESTER`, `ROLE_DBA`, `ROLE_DBA_ADMIN` y `ROLE_ADMIN`.

| Rol | Puede |
|---|---|
| `solicitante` | Explorar la estructura y crear solicitudes |
| `dba` | Aprobar/ejecutar solo en los esquemas que se le asignen en DBA Manager |
| `dba_admin` | Aprobar/ejecutar cualquier solicitud |
| `admin` | Administrar conexiones, usuarios, roles, alcances y respaldos |

## Paso 5. Asignar roles a las personas

**Users → (usuario) → Role mapping → Assign role**.
- Opción A: filtra *by realm roles*.
- Opción B: filtra *by clients* y busca `dbamanager-web`.

Recomendado: crea **grupos** (por ejemplo `DBA Manager - DBAs`), asígnales el rol en *Groups → (grupo) → Role mapping*, y agrega a las personas al grupo. Con LDAP/Active Directory, los grupos federados funcionan igual.

## Paso 6 (opcional). Asignar roles desde DBA Manager

La pantalla **Administración → Usuarios y roles** asigna roles **con el usuario de Keycloak del admin que inició sesión** (`KEYCLOAK_ROLES_ADMIN_MODE=token`). No requiere usuario master ni secretos. Cada cambio queda hecho con la identidad de esa persona.

A cada admin de DBA Manager que deba poder hacerlo:

1. **Users → (admin) → Role mapping → Assign role → Filter by clients**.
2. Busca `realm-management` y asigna **`view-users`**, **`query-users`** y **`manage-users`**. Si usas la opción B de roles, asigna también **`view-clients`**.
3. Esa persona debe cerrar sesión en DBA Manager y volver a entrar.

Si prefieres no dar esos permisos, usa `KEYCLOAK_ROLES_ADMIN_MODE=off` y asigna los roles en la consola de Keycloak (paso 5).

## Paso 7. Valores para `.env`

| Variable | Dónde se ve en Keycloak | Ejemplo |
|---|---|---|
| `KEYCLOAK_PUBLIC_URL` | URL de tu Keycloak, sin `/realms/...` | `https://sso.empresa.com` |
| `KEYCLOAK_REALM` | Nombre del realm (selector arriba a la izquierda) | `corp` |
| `KEYCLOAK_CLIENT_ID` | Client ID del paso 1 | `dbamanager-web` |
| `OIDC_AUDIENCE` | Paso 3 | `dbamanager-web` |
| `KEYCLOAK_ROLES_SOURCE` | Paso 4 | `realm` o `client` |
| `KEYCLOAK_INTERNAL_URL` | Solo si el servidor de DBA Manager llega a Keycloak por otra dirección | vacío |

Comprobación rápida: abre `https://<KEYCLOAK_PUBLIC_URL>/realms/<KEYCLOAK_REALM>/.well-known/openid-configuration`. El campo `issuer` debe ser exactamente `<KEYCLOAK_PUBLIC_URL>/realms/<KEYCLOAK_REALM>`.

## Verificación y problemas comunes

Al arrancar, DBA Manager revisa la configuración y muestra en su log `[OK]`, `[AVISO]` o `[ERROR]`.

| Síntoma | Causa y solución |
|---|---|
| `[AVISO] El issuer de Keycloak es '...' pero DBA Manager espera '...'` | `KEYCLOAK_PUBLIC_URL` no coincide con la URL real (http/https, puerto, ruta). Cópiala del campo `issuer`, quitando `/realms/<realm>`. |
| Keycloak muestra "Invalid parameter: redirect_uri" | Falta la URL de DBA Manager en *Valid redirect URIs* (paso 1). |
| Al entrar aparece "Token inválido: Invalid audience" | Falta el mapper del paso 3, o deja `OIDC_AUDIENCE=` vacío. |
| Inicia sesión pero no ve menús de DBA/Administración | El usuario no tiene los roles (paso 5), o `KEYCLOAK_ROLES_SOURCE` / `ROLE_*` no coinciden con lo creado. Hay que cerrar sesión y volver a entrar después de asignarlos. |
| En *Usuarios y roles*: "Tu usuario de Keycloak no tiene permisos…" | Falta el paso 6 para ese admin, o no volvió a iniciar sesión. |
