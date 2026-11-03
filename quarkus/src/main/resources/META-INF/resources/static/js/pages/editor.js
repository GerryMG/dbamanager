import { api, meta } from "../api.js";
import { h, clear, field, select, modal, busy, errorBox, toast, badge } from "../ui.js";
import { go } from "../app.js";
import { opForm, blankOp } from "./opforms.js";

export async function editorPage(id) {
  const [m, conns, projects] = await Promise.all([meta(), api.get("/api/connections"), api.get("/api/projects")]);
  if (!conns.length) return h("div", { class: "card" }, "No hay conexiones activas. Un administrador debe darlas de alta.");
  let req = { title: "", connection_id: conns[0].id, project_id: null, reason: "", purpose: "", ticket_ref: "", operations: [] };
  if (id) {
    const d = await api.get(`/api/requests/${id}`);
    if (!d.permissions.edit) return h("div", { class: "alert warn" }, "Esta solicitud ya no se puede editar.");
    req = { title: d.title, connection_id: d.connection.id, project_id: d.project?.id ?? null, reason: d.reason, purpose: d.purpose || "",
      ticket_ref: d.ticket_ref || "", operations: d.operations.map(o => o.payload) };
  } else {
    const pre = sessionStorage.getItem("dbam.prefill");
    if (pre) {
      sessionStorage.removeItem("dbam.prefill");
      const p = JSON.parse(pre);
      req.connection_id = p.connection_id;
      req.operations = p.operations;
    }
  }
  const labels = Object.fromEntries(m.operations.map(o => [o.type, o]));
  const opsBox = h("div"), result = h("div"), errBox = h("div");

  function renderOps() {
    clear(opsBox, req.operations.length ? null : h("div", { class: "empty" }, "Agrega operaciones con el selector de abajo, o importa un script SQL."),
      req.operations.map((op, i) => h("div", { class: "op" },
        h("div", { class: "head" }, h("span", { class: "seq" }, `#${i + 1}`), h("b", {}, labels[op.type]?.label || op.type),
          labels[op.type]?.destructive ? badge("destructiva", "warn") : null, h("span", { class: "right" }),
          h("button", { class: "small", disabled: i === 0, onclick: () => move(i, -1), title: "Subir" }, "↑"),
          h("button", { class: "small", disabled: i === req.operations.length - 1, onclick: () => move(i, 1), title: "Bajar" }, "↓"),
          h("button", { class: "small", onclick: () => { req.operations.splice(i + 1, 0, structuredClone(op)); renderOps(); } }, "Duplicar"),
          h("button", { class: "small danger", onclick: () => { req.operations.splice(i, 1); renderOps(); } }, "Quitar")),
        h("div", { class: "body" }, opForm(op, m, { loadPrevious })))));
  }

  function move(i, d) {
    const [x] = req.operations.splice(i, 1);
    req.operations.splice(i + d, 0, x);
    renderOps();
  }

  async function loadPrevious(op) {
    if (!op.schema || !op.table || !op.column?.name) return toast("Indica esquema, tabla y columna", "err");
    try {
      const t = await api.get(`/api/explorer/${req.connection_id}/schemas/${op.schema}/tables/${op.table}`);
      const c = t.columns.find(x => x.name === op.column.name.toUpperCase());
      if (!c) return toast("No existe esa columna", "err");
      op.previous = { name: c.name, data_type: c.data_type, length: c.length, char_semantics: c.char_semantics, precision: c.precision, scale: c.scale,
        nullable: c.nullable, default: c.default_value, default_on_null: c.default_on_null, comment: c.comments, identity: c.identity,
        identity_mode: c.identity_mode || "BY_DEFAULT_ON_NULL", identity_start: c.identity_start ?? null, identity_increment: c.identity_increment ?? null,
        virtual_expression: c.virtual_expression, invisible: c.invisible };
      op.column = structuredClone(op.previous);
      renderOps();
    } catch (e) {
      toast(e.message, "err");
    }
  }

  const input = (key, attrs = {}) => {
    const i = h(attrs.tag || "input", { value: req[key] ?? "", ...attrs, tag: null });
    i.addEventListener("input", () => req[key] = i.value);
    return i;
  };

  const addSel = select([["", "Agregar operación…"], ...m.operations.map(o => [o.type, o.label])], "", v => {
    if (!v) return;
    req.operations.push(blankOp(v));
    addSel.value = "";
    renderOps();
    opsBox.lastElementChild?.scrollIntoView({ behavior: "smooth", block: "center" });
  });

  async function preview(withPreflight, btn) {
    clear(errBox);
    clear(result);
    await busy(btn, api.post("/api/requests/preview", { connection_id: req.connection_id, operations: req.operations, preflight: withPreflight })
      .then(r => clear(result, h("div", { class: "card" },
        withPreflight ? findingsView(r.findings) : null,
        h("h2", {}, "SQL que se ejecutará, en este orden"), h("pre", {}, r.script))))
      .catch(e => clear(errBox, errorBox(e))));
  }

  async function save(submit, btn) {
    clear(errBox);
    const body = { ...req, project_id: req.project_id || null, submit };
    await busy(btn, (id ? api.put(`/api/requests/${id}`, body) : api.post("/api/requests", body))
      .then(d => { toast(submit ? "Solicitud enviada a aprobación" : "Borrador guardado"); go(`#/solicitudes/${d.id}`); })
      .catch(e => { clear(errBox, errorBox(e)); errBox.scrollIntoView({ behavior: "smooth" }); }));
  }

  function importDialog() {
    const sql = h("textarea", { class: "code", placeholder: "Pega aquí el script (CREATE TABLE, ALTER, GRANT, índices, código PL/SQL…)", style: { minHeight: "260px" } });
    const ds = h("input", { placeholder: "Esquema para objetos sin prefijo (opcional)" });
    const out = h("div");
    const go2 = h("button", { class: "primary" }, "Convertir");
    const file = h("input", { type: "file", accept: ".sql,.txt" });
    file.addEventListener("change", async () => { if (file.files[0]) sql.value = await file.files[0].text(); });
    go2.addEventListener("click", () => busy(go2, api.post("/api/requests/import-sql", { connection_id: req.connection_id, sql: sql.value, default_schema: ds.value })
      .then(r => {
        clear(out, h("div", { class: "alert info" }, `${r.recognized} operación(es) visuales y ${r.custom} como SQL personalizado.`),
          r.operations.some(o => o.notes.length) ? h("ul", { class: "small" }, r.operations.flatMap((o, i) => o.notes.map(n => h("li", {}, `#${i + 1} ${o.op.type}: ${n}`)))) : null,
          h("button", { class: "primary", onclick: () => { req.operations.push(...r.operations.map(o => o.op)); renderOps(); dlg.close(); toast("Operaciones agregadas; revísalas antes de enviar"); } }, "Agregar a la solicitud"));
      }).catch(e => clear(out, errorBox(e)))));
    const dlg = modal("Importar script SQL", h("div", { class: "stack" },
      h("p", { class: "muted small" }, "Se convierte a operaciones visuales editables; lo que no se pueda representar queda como SQL personalizado. No se ejecuta nada."),
      file, sql, ds, out), [go2], { wide: true });
  }

  renderOps();
  return h("div", {},
    h("h1", {}, id ? "Editar solicitud" : "Nueva solicitud"),
    h("div", { class: "card" }, h("div", { class: "grid2" },
      field("Título", input("title", { maxlength: 200 }), { required: true }),
      field("Conexión (base destino)", select(conns.map(c => [c.id, `${c.name} (${c.environment})`]), req.connection_id, v => req.connection_id = +v), { required: true }),
      field("Proyecto", select([["", "(sin proyecto)"], ...projects.filter(p => p.active).map(p => [p.id, `${p.code} — ${p.name}`])], req.project_id ?? "", v => req.project_id = v ? +v : null)),
      field("Ticket / referencia", input("ticket_ref", { maxlength: 100 }))),
      h("div", { class: "grid2", style: { marginTop: "10px" } },
        field("Motivo: ¿por qué se necesita?", input("reason", { tag: "textarea", maxlength: 4000 }), { required: true }),
        field("Propósito: ¿para qué / con qué fin?", input("purpose", { tag: "textarea", maxlength: 4000 })))),
    h("div", { class: "row spread" }, h("h2", {}, "Operaciones"), h("div", { class: "row" }, h("button", { onclick: importDialog }, "Importar script SQL"))),
    h("p", { class: "small muted" }, "Se ejecutan en el orden mostrado: si una depende de otra (p. ej. un índice sobre una tabla nueva), ponla después."),
    opsBox, h("div", { style: { maxWidth: "420px", margin: "6px 0 16px" } }, addSel),
    errBox,
    h("div", { class: "row" },
      h("button", { onclick: e => preview(false, e.currentTarget) }, "Ver SQL"),
      h("button", { onclick: e => preview(true, e.currentTarget) }, "Validar contra la base"),
      h("span", { class: "right" }),
      h("button", { onclick: e => save(false, e.currentTarget) }, "Guardar borrador"),
      h("button", { class: "primary", onclick: e => save(true, e.currentTarget) }, "Enviar a aprobación")),
    h("div", { style: { marginTop: "14px" } }, result));
}

export function findingsView(findings) {
  if (!findings?.length) return h("div", { class: "alert ok" }, "Validación previa sin observaciones.");
  const kind = { error: "err", warning: "warn", info: "info" };
  return h("div", {}, h("h2", {}, "Validación previa"), findings.map(f => h("div", { class: "alert " + kind[f.level] },
    h("b", {}, `#${f.op_seq} `), f.message)));
}
