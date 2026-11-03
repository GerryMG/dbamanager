import { api } from "../api.js";
import { h, clear, table, badge, fmtDate, modal, busy, errorBox, toast, field, select, check, download, confirmDialog } from "../ui.js";
import { state } from "../app.js";
import { signFlow } from "../webauthn.js";

export async function adminPage() {
  const me = state.me;
  const tabs = [
    me.is_admin ? ["Conexiones", connectionsTab] : null,
    ["Proyectos", projectsTab],
    ["Alcances de DBAs", scopesTab],
    me.is_admin ? ["Usuarios y roles", usersTab] : null,
    me.is_admin ? ["Respaldo", backupTab] : null,
  ].filter(Boolean);
  const bar = h("div", { class: "tabs" }), body = h("div");
  const show = i => {
    [...bar.children].forEach((b, j) => b.classList.toggle("active", i === j));
    sessionStorage.setItem("dbam.admintab", i);
    clear(body, h("div", { class: "muted" }, "Cargando…"));
    tabs[i][1]().then(n => clear(body, n)).catch(e => clear(body, errorBox(e)));
  };
  tabs.forEach(([l], i) => bar.append(h("button", { onclick: () => show(i) }, l)));
  show(Math.min(+(sessionStorage.getItem("dbam.admintab") || 0), tabs.length - 1));
  return h("div", {}, h("h1", {}, "Administración"), bar, body);
}

// ------------------------------------------------------------------ conexiones
async function connectionsTab() {
  const rows = await api.get("/api/admin/connections");
  const box = h("div");
  const render = list => clear(box, h("div", { class: "card" }, h("div", { class: "row spread" }, h("h2", {}, "Conexiones"),
    h("button", { class: "primary", onclick: () => edit(null) }, "Nueva conexión")),
    table([["Nombre", c => h("b", {}, c.name)], ["Ambiente", c => c.environment], ["Servidor", c => `${c.host}:${c.port}/${c.service_name}`],
      ["Versión", c => c.server_version || "—"], ["Cuentas", c => [c.catalog_user, c.exec_user ? " · " + c.exec_user : ""].join("")],
      ["", c => [c.allow_execution ? badge("ejecución", "info") : null, c.use_tls ? badge("TLS", "ok") : null, c.active ? null : badge("inactiva")]],
      ["", c => h("div", { class: "row" }, h("button", { class: "small", onclick: () => test(c, "catalog") }, "Probar catálogo"),
        c.exec_user ? h("button", { class: "small", onclick: () => test(c, "exec") }, "Probar ejecutora") : null,
        h("button", { class: "small", onclick: () => edit(c) }, "Editar"))]], list)));
  render(rows);

  async function test(c, role) {
    const r = await api.post(`/api/admin/connections/${c.id}/test?role=${role}`);
    modal(`Prueba: ${c.name} (${role === "catalog" ? "catálogo" : "ejecutora"})`, r.ok
      ? h("div", {}, h("div", { class: "alert ok" }, "Conexión correcta"), h("div", { class: "kv" }, "Versión", r.version, "Soportada", r.supported ? "sí" : "no (requiere 12.1+)",
        "Contenedor", r.container || "", "Banner", r.banner || ""), h("h3", {}, "Privilegios de la sesión"), h("pre", {}, (r.session_privileges || []).join("\n")))
      : h("div", { class: "alert err" }, r.error));
  }

  function edit(c) {
    const d = c ? { ...c, catalog_password: "", exec_password: "" } : { name: "", environment: "DEV", description: "", host: "", port: 1521, service_name: "",
      catalog_user: "DBAMGR_CATALOG", catalog_password: "", exec_user: "", exec_password: "", allow_execution: false, use_tls: false, required_approvals: 1, active: true };
    const inp = (k, attrs = {}) => { const i = h("input", { value: d[k] ?? "", ...attrs }); i.addEventListener("input", () => d[k] = attrs.type === "number" ? +i.value : i.value); return i; };
    const err = h("div");
    const save = h("button", { class: "primary" }, "Guardar");
    const m = modal(c ? "Editar conexión" : "Nueva conexión", h("div", { class: "stack" }, h("div", { class: "grid2" },
      field("Nombre", inp("name"), { required: true }), field("Ambiente (DEV, QA, PROD…)", inp("environment"), { required: true }),
      field("Host", inp("host"), { required: true }), field("Puerto", inp("port", { type: "number" })),
      field("Servicio (service name / PDB)", inp("service_name"), { required: true }), field("Descripción", inp("description")),
      field("Cuenta de catálogo (solo lectura del diccionario)", inp("catalog_user"), { required: true }),
      field(c ? "Contraseña de catálogo (vacío = no cambiar)" : "Contraseña de catálogo", inp("catalog_password", { type: "password", autocomplete: "new-password" }), { required: !c }),
      field("Cuenta ejecutora (opcional)", inp("exec_user")),
      field("Contraseña ejecutora (vacío = no cambiar)", inp("exec_password", { type: "password", autocomplete: "new-password" })),
      field("Aprobaciones requeridas", inp("required_approvals", { type: "number", min: 1, max: 5 }))),
      h("div", { class: "row" }, check("Permitir ejecución desde DBA Manager", d.allow_execution, v => d.allow_execution = v),
        check("Conexión cifrada (TLS)", d.use_tls, v => d.use_tls = v), check("Activa", d.active, v => d.active = v)),
      h("p", { class: "small muted" }, "Si cambias el servidor, el servicio o la cuenta ejecutora, lo aprobado sin ejecutar deja de valer y debe aprobarse de nuevo."), err), [save], { wide: true });
    save.addEventListener("click", () => busy(save, (c ? api.put(`/api/admin/connections/${c.id}`, d) : api.post("/api/admin/connections", d))
      .then(async () => { m.close(); toast("Conexión guardada"); render(await api.get("/api/admin/connections")); })
      .catch(e => err.replaceChildren(errorBox(e)))));
  }
  return box;
}

// ------------------------------------------------------------------ proyectos
async function projectsTab() {
  const box = h("div");
  const load = async () => {
    const rows = await api.get("/api/projects");
    clear(box, h("div", { class: "card" }, h("div", { class: "row spread" }, h("h2", {}, "Proyectos"),
      state.me.is_dba ? h("button", { class: "primary", onclick: () => edit(null) }, "Nuevo proyecto") : null),
      table([["Código", p => h("b", {}, p.code)], ["Nombre", p => p.name], ["Responsable", p => p.owner || ""], ["Descripción", p => p.description || ""],
        ["", p => p.active ? null : badge("inactivo")], ["", p => state.me.is_dba ? h("button", { class: "small", onclick: () => edit(p) }, "Editar") : null]], rows)));
  };
  function edit(p) {
    const d = p ? { ...p } : { code: "", name: "", description: "", owner: "", active: true };
    const inp = k => { const i = h("input", { value: d[k] ?? "" }); i.addEventListener("input", () => d[k] = i.value); return i; };
    const err = h("div"), save = h("button", { class: "primary" }, "Guardar");
    const m = modal(p ? "Editar proyecto" : "Nuevo proyecto", h("div", { class: "stack" }, field("Código", inp("code"), { required: true }), field("Nombre", inp("name"), { required: true }),
      field("Responsable", inp("owner")), field("Descripción", inp("description")), check("Activo", d.active, v => d.active = v), err), [save]);
    save.addEventListener("click", () => busy(save, (p ? api.put(`/api/admin/projects/${p.id}`, d) : api.post("/api/admin/projects", d))
      .then(() => { m.close(); toast("Proyecto guardado"); load(); }).catch(e => err.replaceChildren(errorBox(e)))));
  }
  await load();
  return box;
}

// ------------------------------------------------------------------ alcances (los firma un DBA principal)
async function scopesTab() {
  const box = h("div");
  const [conns, users] = await Promise.all([api.get("/api/connections"), api.get("/api/admin/users")]);
  const cname = id => conns.find(c => c.id === id)?.name || `#${id}`;
  const load = async () => {
    const rows = await api.get("/api/admin/scopes");
    clear(box, h("div", { class: "card" }, h("div", { class: "row spread" }, h("h2", {}, "Alcances de aprobación"),
      state.me.is_dba_admin ? h("button", { class: "primary", onclick: add }, "Otorgar alcance") : null),
      h("p", { class: "small muted" }, "Un DBA con rol dba aprueba y ejecuta solo en los esquemas que se le asignen aquí. Cada alcance lo otorga un DBA principal con su llave; los que no tienen firma válida no cuentan."),
      table([["DBA", s => h("b", {}, s.username)], ["Conexión", s => cname(s.connection_id)], ["Esquema", s => s.schema_name === "*" ? "Todos (*)" : s.schema_name],
        ["Otorgó", s => `${s.granted_by || ""} · ${fmtDate(s.granted_at)}`],
        ["Firma", s => s.signature_valid ? h("span", { class: "seal" }, badge("válida", "ok"), h("code", {}, s.key)) : badge("sin firma válida", "err")],
        ["", s => state.me.is_dba_admin ? h("button", { class: "small danger", onclick: async () => {
          if (!await confirmDialog("Quitar alcance", `¿Quitar a ${s.username} el alcance sobre ${s.schema_name}?`, "Quitar", true)) return;
          await api.del(`/api/admin/scopes/${s.id}`).then(() => { toast("Alcance quitado"); load(); }).catch(e => toast(e.message, "err"));
        } }, "Quitar") : null]], rows)));
  };
  function add() {
    const d = { username: "", connection_id: conns[0]?.id, schema_name: "" };
    const dbas = users.filter(u => u.roles.includes("dba") && u.username !== state.me.username);
    const userSel = dbas.length ? select([["", "Elige un DBA"], ...dbas.map(u => [u.username, u.full_name ? `${u.username} (${u.full_name})` : u.username])], "", v => d.username = v)
      : h("input", { placeholder: "usuario", oninput: e => d.username = e.target.value.trim() });
    const schema = h("input", { placeholder: "ESQUEMA o * para todos", oninput: e => d.schema_name = e.target.value.trim().toUpperCase() });
    const next = h("button", { class: "primary" }, "Continuar y firmar");
    const m = modal("Otorgar alcance", h("div", { class: "stack" }, field("DBA", userSel, { required: true }),
      field("Conexión", select(conns.map(c => [c.id, c.name]), d.connection_id, v => d.connection_id = +v)), field("Esquema", schema, { required: true }),
      dbas.length ? null : h("p", { class: "small muted" }, "Los DBAs aparecen aquí después de iniciar sesión por primera vez.")), [next]);
    next.addEventListener("click", () => {
      m.close();
      signFlow("Firmar alcance", () => api.post("/api/admin/scopes/challenge", d),
        (token, assertion) => api.post("/api/admin/scopes", { ...d, token, assertion }))
        .then(() => { toast("Alcance otorgado"); load(); })
        .catch(e => { if (e.message !== "cancelado") toast(e.message, "err"); });
    });
  }
  await load();
  return box;
}

// ------------------------------------------------------------------ usuarios y roles (Keycloak)
async function usersTab() {
  const box = h("div");
  const status = await api.get("/api/admin/identity");
  const ROLES = ["solicitante", "dba", "dba_admin", "admin"];
  const search = h("input", { placeholder: "Buscar usuario en Keycloak" });
  const list = h("div");
  const load = async () => {
    clear(list, h("div", { class: "muted" }, "Cargando…"));
    try {
      const users = await api.get("/api/admin/idp-users?" + new URLSearchParams({ search: search.value.trim() }));
      clear(list, table([["Usuario", u => h("div", {}, h("b", {}, u.username), h("div", { class: "small muted" }, u.full_name || u.email || ""))],
        ["Roles", u => h("div", { class: "row" }, ROLES.map(r => {
          const inherited = u.roles.includes(r) && !u.direct_roles.includes(r);
          return check(r + (inherited ? " (por grupo)" : ""), u.roles.includes(r), async on => {
            const wanted = new Set(u.direct_roles);
            on ? wanted.add(r) : wanted.delete(r);
            try {
              const res = await api.put(`/api/admin/idp-users/${u.id}/roles`, { roles: [...wanted] });
              u.direct_roles = [...wanted];
              u.roles = res.roles;
              toast(res.note || `Roles de ${u.username} actualizados`, res.note ? "err" : "ok");
            } catch (e) {
              toast(e.message, "err");
              load();
            }
          });
        }))],
        ["Alcances", u => String(u.scopes)], ["Último acceso", u => fmtDate(u.last_seen)]], users));
    } catch (e) {
      clear(list, errorBox(e));
    }
  };
  search.addEventListener("keydown", e => { if (e.key === "Enter") load(); });
  clear(box, h("div", { class: "card" }, h("h2", {}, "Proveedor de identidad"), h("div", { class: "kv" },
    "Emisor", status.issuer, "Cliente", status.client_id, "Roles en", status.roles_source === "client" ? `cliente ${status.roles_client}` : "realm",
    "Asignación desde la app", status.management_enabled ? (status.management_ok ? "operativa" : "sin permisos") : "deshabilitada"),
    status.management_error ? h("div", { class: "alert warn" }, typeof status.management_error === "string" ? status.management_error : JSON.stringify(status.management_error)) : null,
    status.missing_roles?.length ? h("div", { class: "alert warn" }, "Roles que no existen en Keycloak: " + status.missing_roles.join(", ")) : null),
    status.management_enabled && status.management_ok ? h("div", { class: "card" }, h("h2", {}, "Usuarios y roles"),
      h("p", { class: "small muted" }, "No puedes cambiar tus propios roles. Un rol dba o dba_admin no basta para aprobar: la persona además necesita su llave avalada por un DBA principal."),
      h("div", { style: { marginBottom: "10px" } }, search), list) : null);
  if (status.management_enabled && status.management_ok) load();
  return box;
}

// ------------------------------------------------------------------ respaldo
async function backupTab() {
  const file = h("input", { type: "file", accept: ".json" });
  let replace = false;
  const out = h("div");
  const exp = h("button", { class: "primary" }, "Descargar respaldo");
  exp.addEventListener("click", () => busy(exp, api.get("/api/admin/backup", { raw: true }).then(async res => {
    const name = (res.headers.get("Content-Disposition") || "").match(/filename="([^"]+)"/)?.[1] || "dbamanager-metadata.json";
    download(name, await res.text(), "application/json");
  }).catch(e => toast(e.message, "err"))));
  const imp = h("button", {}, "Restaurar");
  imp.addEventListener("click", () => {
    if (!file.files[0]) return toast("Elige el archivo de respaldo", "err");
    const form = new FormData();
    form.append("file", file.files[0]);
    busy(imp, api.upload(`/api/admin/restore?replace=${replace}`, form).then(r => clear(out, h("div", { class: "alert ok" }, "Restaurado: " +
      Object.entries(r.rows).map(([k, v]) => `${k} ${v}`).join(", ")))).catch(e => clear(out, errorBox(e))));
  });
  return h("div", {}, h("div", { class: "card" }, h("h2", {}, "Respaldo de la metadata"),
    h("p", { class: "muted" }, "Archivo JSON firmado con todas las solicitudes, aprobaciones con sus firmas, llaves públicas, bitácora y conexiones (contraseñas cifradas). Para restaurar se necesita la misma SECRETS_KEY."),
    exp),
    h("div", { class: "card" }, h("h2", {}, "Restaurar"), h("p", { class: "muted" }, "Solo sobre una metadata vacía, salvo que METADATA_RESTORE_REPLACE lo permita. Tras restaurar, lo aprobado sin ejecutar debe aprobarse de nuevo y cada DBA vuelve a activar su ejecución."),
      h("div", { class: "stack" }, file, check("Reemplazar la metadata existente", false, v => replace = v), h("div", {}, imp), out)));
}
