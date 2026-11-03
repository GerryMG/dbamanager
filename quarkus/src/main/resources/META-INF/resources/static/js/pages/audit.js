import { api } from "../api.js";
import { h, clear, table, fmtDate, errorBox } from "../ui.js";

export async function auditPage() {
  const f = { username: "", action: "", entity_type: "" };
  const out = h("div");
  const inputs = Object.keys(f).map(k => {
    const i = h("input", { placeholder: { username: "Usuario", action: "Acción (p. ej. REQUEST_APPROVED)", entity_type: "Entidad (request, key, scope…)" }[k] });
    i.addEventListener("change", () => { f[k] = i.value.trim(); load(); });
    return h("div", { style: { flex: "1", minWidth: "180px" } }, i);
  });

  async function load() {
    clear(out, h("div", { class: "muted" }, "Cargando…"));
    try {
      const rows = await api.get("/api/admin/audit?" + new URLSearchParams(Object.entries(f).filter(([, v]) => v)));
      clear(out, h("div", { class: "card" }, table([
        ["Fecha", r => fmtDate(r.at)],
        ["Usuario", r => r.username],
        ["Acción", r => h("code", {}, r.action)],
        ["Entidad", r => `${r.entity_type}${r.entity_id ? " #" + r.entity_id : ""}`],
        ["Detalle", r => r.details ? h("pre", { style: { maxWidth: "520px", maxHeight: "140px" } }, JSON.stringify(r.details, null, 1)) : ""],
        ["IP", r => r.ip || ""],
      ], rows, { empty: "Sin eventos" })));
    } catch (e) {
      clear(out, errorBox(e));
    }
  }
  load();
  return h("div", {}, h("h1", {}, "Bitácora"), h("p", { class: "muted" }, "Quién solicitó, aprobó, firmó y ejecutó cada cambio."),
    h("div", { class: "row", style: { marginBottom: "12px" } }, inputs), out);
}
