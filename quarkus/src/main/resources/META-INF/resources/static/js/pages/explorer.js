import { api } from "../api.js";
import { h, clear, table, badge, select, errorBox, fmtDate } from "../ui.js";
import { go } from "../app.js";

const TYPE_LABEL = { TABLE: "Tablas", VIEW: "Vistas", "MATERIALIZED VIEW": "Vistas materializadas", SEQUENCE: "Secuencias",
  PROCEDURE: "Procedimientos", FUNCTION: "Funciones", PACKAGE: "Paquetes", SYNONYM: "Sinónimos", TRIGGER: "Triggers", TYPE: "Tipos" };

/** Abre el editor con una operación precargada. */
export function requestWith(connectionId, op) {
  sessionStorage.setItem("dbam.prefill", JSON.stringify({ connection_id: connectionId, operations: [op] }));
  go("#/solicitudes/nueva");
}

export async function explorerPage() {
  const conns = await api.get("/api/connections");
  if (!conns.length) return h("div", { class: "card" }, "No hay conexiones activas. Un administrador debe darlas de alta.");
  let cid = +(sessionStorage.getItem("dbam.cid") || conns[0].id);
  if (!conns.some(c => c.id === cid)) cid = conns[0].id;
  const schemasEl = h("div"), objectsEl = h("div"), detailEl = h("div");
  const search = h("input", { placeholder: "Buscar tablas, columnas o comentarios en todos los esquemas" });
  let schemas = [], selSchema = null, selObject = null;

  async function loadSchemas() {
    sessionStorage.setItem("dbam.cid", cid);
    clear(schemasEl, h("div", { class: "muted" }, "Cargando esquemas…"));
    clear(objectsEl);
    clear(detailEl);
    try {
      schemas = await api.get(`/api/explorer/${cid}/schemas`);
      renderSchemas("");
    } catch (e) {
      clear(schemasEl, errorBox(e));
    }
  }

  function renderSchemas(filter) {
    const f = h("input", { placeholder: "Filtrar esquemas", value: filter });
    f.addEventListener("input", () => { list.replaceChildren(...items(f.value)); });
    const items = q => schemas.filter(s => s.name.includes(q.toUpperCase())).map(s =>
      h("a", { class: selSchema === s.name ? "sel" : "", onclick: () => openSchema(s.name) }, s.name,
        h("span", { class: "right small muted" }, `${s.objects} obj`),
        s.kind === "READER" ? badge("lector", "info") : s.kind === "EMPTY" ? badge("vacío") : null));
    const list = h("div", { class: "list" }, items(filter));
    clear(schemasEl, h("div", { class: "stack" }, f, list));
  }

  async function openSchema(name) {
    selSchema = name;
    selObject = null;
    renderSchemas("");
    clear(objectsEl, h("div", { class: "muted" }, "Cargando objetos…"));
    try {
      const objs = await api.get(`/api/explorer/${cid}/schemas/${name}/objects`);
      const groups = {};
      for (const o of objs) (groups[o.type] ||= []).push(o);
      clear(objectsEl, h("div", { class: "list" },
        h("a", { onclick: () => openUser(name) }, h("b", {}, "Usuario y permisos de " + name)),
        Object.entries(groups).flatMap(([t, list]) => [
          h("div", { class: "item small muted", style: { cursor: "default", background: "var(--panel-2)" } }, `${TYPE_LABEL[t] || t} (${list.length})`),
          ...list.map(o => h("a", { onclick: () => openObject(name, o) }, o.name,
            o.status === "INVALID" ? badge("inválido", "err") : null,
            o.type === "SYNONYM" ? h("span", { class: "small muted" }, `→ ${o.target_schema}.${o.target_object}`) : null)),
        ])));
      if (!objs.length) objectsEl.append(h("div", { class: "empty" }, "Sin objetos"));
    } catch (e) {
      clear(objectsEl, errorBox(e));
    }
  }

  async function openUser(name) {
    clear(detailEl, h("div", { class: "muted" }, "Cargando…"));
    try {
      const u = await api.get(`/api/explorer/${cid}/schemas/${name}/user`);
      clear(detailEl, h("div", { class: "card" },
        h("div", { class: "row spread" }, h("h2", {}, "Usuario " + u.username),
          h("button", { onclick: () => requestWith(cid, { type: "GRANT", schema: "", object: "", grantee: name, privileges: ["SELECT"], columns: [] }) }, "Solicitar permisos para este usuario")),
        h("div", { class: "kv" }, "Estado", u.account_status, "Creado", fmtDate(u.created), "Tablespace", u.default_tablespace || "", "Perfil", u.profile || ""),
        h("h3", {}, "Roles"), table([["Rol", r => r.role], ["Admin", r => r.admin_option], ["Por defecto", r => r.default_role]], u.roles),
        h("h3", {}, "Privilegios de sistema"), table([["Privilegio", r => r.privilege], ["Admin", r => r.admin_option]], u.system_privileges),
        h("h3", {}, "Permisos sobre objetos"), table([["Objeto", r => `${r.owner}.${r.object_name}`], ["Tipo", r => r.object_type || ""], ["Privilegios", r => r.privileges], ["Con grant", r => r.grantable]], u.object_privileges),
        h("h3", {}, "Sinónimos"), table([["Sinónimo", r => r.name], ["Apunta a", r => `${r.target_schema}.${r.target_object}`]], u.synonyms)));
    } catch (e) {
      clear(detailEl, errorBox(e));
    }
  }

  async function openObject(schema, o) {
    selObject = o;
    if (["TABLE", "VIEW", "MATERIALIZED VIEW"].includes(o.type)) return openTable(schema, o.name);
    clear(detailEl, h("div", { class: "card" }, h("h2", {}, `${o.type} ${schema}.${o.name}`), h("div", { class: "muted" }, "Cargando DDL…")));
    try {
      const d = await api.get(`/api/explorer/${cid}/schemas/${schema}/ddl?` + new URLSearchParams({ type: o.type, name: o.name }));
      clear(detailEl, h("div", { class: "card" }, h("div", { class: "row spread" }, h("h2", {}, `${o.type} ${schema}.${o.name}`),
        o.type !== "SYNONYM" && o.type !== "SEQUENCE" ? h("button", { onclick: () => requestWith(cid, { type: "CREATE_OR_REPLACE_CODE", schema, kind: o.type, name: o.name, source: d.ddl.replace(/;\s*$/, "") }) }, "Solicitar cambio") : null),
        h("pre", {}, d.ddl)));
    } catch (e) {
      clear(detailEl, errorBox(e));
    }
  }

  async function openTable(schema, name) {
    clear(detailEl, h("div", { class: "muted" }, "Cargando…"));
    let t;
    try {
      t = await api.get(`/api/explorer/${cid}/schemas/${schema}/tables/${name}`);
    } catch (e) {
      clear(detailEl, errorBox(e));
      return;
    }
    const isTable = t.type === "TABLE";
    const tabBody = h("div");
    const tabs = [
      ["Columnas", () => table([
        ["#", c => c.column_id ?? ""], ["Columna", c => h("b", {}, c.name)], ["Tipo", c => c.type_display],
        ["Nulo", c => c.nullable ? "sí" : "no"],
        ["Default / expresión", c => h("code", {}, c.virtual_expression ? `AS (${c.virtual_expression})` : c.identity ? `IDENTITY ${c.identity_mode}` : c.default_value ?? "")],
        ["Comentario", c => c.comments || ""],
        ["", c => isTable ? h("button", { class: "small", onclick: () => modifyColumn(schema, name, c) }, "Modificar") : ""],
      ], t.columns)],
      ["Restricciones", () => h("div", {}, table([
        ["Nombre", c => c.name], ["Tipo", c => ({ P: "PK", U: "Única", R: "Foránea", C: "Check" })[c.type]], ["Columnas", c => c.columns || ""],
        ["Detalle", c => c.type === "R" ? `→ ${c.ref_owner}.${c.ref_table} (${c.ref_columns})${c.delete_rule !== "NO ACTION" ? " ON DELETE " + c.delete_rule : ""}` : c.condition || ""],
        ["Estado", c => [c.enabled ? badge("habilitada", "ok") : badge("deshabilitada", "warn"), c.validated ? null : badge("novalidate")]],
      ], t.constraints), t.referenced_by.length ? [h("h3", {}, "Referenciada por"),
        table([["Tabla", r => `${r.owner}.${r.table_name}`], ["Restricción", r => r.name], ["Columnas", r => r.columns]], t.referenced_by)] : null)],
      ["Índices", () => table([
        ["Nombre", i => i.name], ["Tipo", i => [i.kind, i.function_based ? " · función" : "", i.reverse ? " · reverse" : "", i.indextype ? ` · ${i.indextype}` : ""].join("")],
        ["Columnas", i => h("code", {}, i.columns)], ["Visibilidad", i => i.visibility], ["Estado", i => i.status],
        ["Restricción", i => i.constraint_name || ""],
      ], t.indexes)],
      ["Permisos", () => table([["Usuario / rol", g => g.grantee], ["Privilegios", g => g.privileges.join(", ")],
        ["Por columna", g => g.column_privileges.join(", ")], ["Con grant", g => g.grantable.join(", ")]], t.grants)],
      ["Sinónimos", () => table([["Sinónimo", s => `${s.owner}.${s.name}`]], t.synonyms)],
      ["Particiones", () => t.partitioning ? h("div", {}, h("div", { class: "kv" }, "Tipo", t.partitioning.partitioning_type, "Columnas", t.partitioning.columns || "",
        "Intervalo", t.partitioning.interval || "—"), table([["#", p => p.position], ["Partición", p => p.name], ["Límite", p => h("code", {}, p.high_value || "")], ["Tablespace", p => p.tablespace_name || ""]], t.partitions))
        : h("div", { class: "empty" }, "No está particionada")],
      ["Triggers", () => table([["Trigger", g => `${g.owner}.${g.name}`], ["Tipo", g => g.trigger_type], ["Evento", g => g.triggering_event], ["Estado", g => g.status]], t.triggers)],
      ["DDL", () => {
        const pre = h("pre", {}, "Cargando…");
        api.get(`/api/explorer/${cid}/schemas/${schema}/ddl?` + new URLSearchParams({ type: t.type, name })).then(d => pre.textContent = d.ddl).catch(e => pre.textContent = e.message);
        return pre;
      }],
    ];
    const tabBar = h("div", { class: "tabs" });
    const show = i => {
      [...tabBar.children].forEach((b, j) => b.classList.toggle("active", i === j));
      clear(tabBody, tabs[i][1]());
    };
    tabs.forEach(([label], i) => tabBar.append(h("button", { onclick: () => show(i) }, label)));
    const actions = isTable ? h("div", { class: "row" },
      h("button", { onclick: () => requestWith(cid, { type: "ADD_COLUMN", schema, table: name, column: { name: "", data_type: "VARCHAR2", length: 100, nullable: true } }) }, "Agregar columna"),
      h("button", { onclick: () => requestWith(cid, { type: "CREATE_INDEX", schema, table: name, index_name: "", index_type: "NORMAL", columns: [{ expression: "", descending: false }] }) }, "Crear índice"),
      h("button", { onclick: () => requestWith(cid, { type: "GRANT", schema, object: name, grantee: "", privileges: ["SELECT"], columns: [] }) }, "Otorgar permisos"),
      h("button", { onclick: () => requestWith(cid, { type: "CREATE_SYNONYM", schema: "", synonym: name, target_schema: schema, target_object: name, replace: true }) }, "Crear sinónimo"),
      h("button", { class: "danger", onclick: () => requestWith(cid, { type: "DROP_TABLE", schema, table: name, cascade_constraints: false, purge: false }) }, "Eliminar tabla"))
      : h("div", { class: "row" }, h("button", { onclick: () => requestWith(cid, { type: "GRANT", schema, object: name, grantee: "", privileges: ["SELECT"], columns: [] }) }, "Otorgar permisos"));
    clear(detailEl, h("div", { class: "card" },
      h("div", { class: "row spread" }, h("h2", {}, `${t.type === "TABLE" ? "Tabla" : t.type === "VIEW" ? "Vista" : "Vista materializada"} ${schema}.${name}`),
        t.status === "INVALID" ? badge("inválida", "err") : null),
      t.comments ? h("p", { class: "muted" }, t.comments) : null,
      isTable ? h("p", { class: "small muted" }, [t.tablespace_name && `Tablespace ${t.tablespace_name}`, t.partitioned === "YES" && "particionada",
        t.temporary === "Y" && "temporal", t.compression === "ENABLED" && `compresión ${t.compress_for}`, t.logging === "NO" && "NOLOGGING",
        t.read_only === "YES" && "solo lectura"].filter(Boolean).join(" · ")) : null,
      actions, h("div", { style: { marginTop: "12px" } }, tabBar, tabBody)));
    show(0);
  }

  function modifyColumn(schema, table, c) {
    const col = {
      name: c.name, data_type: c.data_type, length: c.length, char_semantics: c.char_semantics, precision: c.precision, scale: c.scale,
      nullable: c.nullable, default: c.default_value, default_on_null: c.default_on_null, comment: c.comments, identity: c.identity,
      identity_mode: c.identity_mode || "BY_DEFAULT_ON_NULL", identity_start: c.identity_start ?? null, identity_increment: c.identity_increment ?? null,
      virtual_expression: c.virtual_expression, invisible: c.invisible,
    };
    requestWith(cid, { type: "MODIFY_COLUMN", schema, table, column: { ...col }, previous: col });
  }

  const results = h("div");
  search.addEventListener("keydown", async e => {
    if (e.key !== "Enter") return;
    if (search.value.trim().length < 2) return;
    clear(results, h("div", { class: "muted" }, "Buscando…"));
    try {
      const rows = await api.get(`/api/explorer/${cid}/search?q=` + encodeURIComponent(search.value.trim()));
      clear(results, h("div", { class: "card" }, h("div", { class: "row spread" }, h("b", {}, `${rows.length} resultado(s)`), h("button", { class: "link", onclick: () => clear(results) }, "Cerrar")),
        table([["Esquema", r => r.schema], ["Objeto", r => `${r.object_type} ${r.object_name}`], ["Columna", r => r.column_name || ""], ["Comentario", r => r.comments || ""]], rows,
          { onclick: async r => { await openSchema(r.schema); openObject(r.schema, { type: r.object_type, name: r.object_name }); } })));
    } catch (err) {
      clear(results, errorBox(err));
    }
  });

  loadSchemas();
  return h("div", {},
    h("div", { class: "row spread" }, h("h1", {}, "Explorar estructura"),
      h("div", { style: { minWidth: "240px" } }, select(conns.map(c => [c.id, `${c.name} (${c.environment})`]), cid, v => { cid = +v; loadSchemas(); }))),
    h("p", { class: "muted small" }, "Solo estructura: DBA Manager nunca consulta los datos de las tablas."),
    h("div", { style: { marginBottom: "12px" } }, search), results,
    h("div", { class: "split" }, schemasEl, objectsEl, detailEl));
}
