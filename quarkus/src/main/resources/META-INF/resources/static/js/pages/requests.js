import { api } from "../api.js";
import { h, clear, table, statusBadge, fmtDate, select, errorBox } from "../ui.js";
import { go, state } from "../app.js";

const VIEWS = [["all", "Todas"], ["mine", "Mías"], ["to_review", "Por revisar"], ["to_execute", "Por ejecutar"]];
const STATUSES = [["", "Todos los estados"], ["DRAFT", "Borrador"], ["PENDING_APPROVAL", "Pendiente de aprobación"],
  ["CHANGES_REQUESTED", "Cambios solicitados"], ["APPROVED", "Aprobada"], ["EXECUTED", "Ejecutada"], ["FAILED", "Falló"],
  ["REJECTED", "Rechazada"], ["CANCELLED", "Cancelada"]];

export async function requestsPage() {
  const f = { view: sessionStorage.getItem("dbam.view") || (state.me.is_dba ? "to_review" : "mine"), status: "", connection_id: "", q: "" };
  const conns = await api.get("/api/connections");
  const out = h("div");
  const tabs = h("div", { class: "tabs" });
  const q = h("input", { placeholder: "Buscar por título, esquema o usuario", value: "" });
  let timer;
  q.addEventListener("input", () => { clearTimeout(timer); timer = setTimeout(() => { f.q = q.value; load(); }, 300); });

  async function load() {
    sessionStorage.setItem("dbam.view", f.view);
    clear(tabs, VIEWS.map(([k, l]) => h("button", { class: f.view === k ? "active" : "", onclick: () => { f.view = k; load(); } }, l)));
    const params = new URLSearchParams(Object.entries(f).filter(([, v]) => v));
    clear(out, h("div", { class: "muted" }, "Cargando…"));
    try {
      const rows = await api.get("/api/requests?" + params);
      clear(out, h("div", { class: "card" }, table([
        ["Código", r => h("b", {}, r.code)],
        ["Título", r => r.title],
        ["Estado", r => statusBadge(r.status)],
        ["Conexión", r => `${r.connection.name} (${r.connection.environment})`],
        ["Esquemas", r => r.target_schemas.join(", ")],
        ["Solicitó", r => r.requested_by],
        ["Actualizada", r => fmtDate(r.updated_at)],
      ], rows, { onclick: r => go(`#/solicitudes/${r.id}`), empty: "No hay solicitudes en esta vista" })));
    } catch (e) {
      clear(out, errorBox(e));
    }
  }

  const filters = h("div", { class: "row", style: { marginBottom: "12px" } },
    h("div", { style: { flex: "2", minWidth: "220px" } }, q),
    h("div", { style: { flex: "1", minWidth: "180px" } }, select(STATUSES, "", v => { f.status = v; load(); })),
    h("div", { style: { flex: "1", minWidth: "180px" } }, select([["", "Todas las conexiones"], ...conns.map(c => [c.id, c.name])], "", v => { f.connection_id = v; load(); })));
  load();
  return h("div", {},
    h("div", { class: "row spread" }, h("h1", {}, "Solicitudes"), h("button", { class: "primary", onclick: () => go("#/solicitudes/nueva") }, "Nueva solicitud")),
    tabs, filters, out);
}
