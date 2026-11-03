import { api } from "../api.js";
import { h, clear, statusBadge, badge, fmtDate, modal, busy, errorBox, toast, field, download, table } from "../ui.js";
import { go } from "../app.js";
import { signFlow } from "../webauthn.js";
import { findingsView } from "./editor.js";

const DECISION = { APPROVED: ["Aprobó", "ok"], REJECTED: ["Rechazó", "err"], CHANGES_REQUESTED: ["Pidió cambios", "warn"] };

export async function detailPage(id) {
  const root = h("div");
  let r;

  async function load() {
    r = await api.get(`/api/requests/${id}`);
    render();
  }

  function keyMissing() {
    return h("div", { class: "alert warn" }, "Para decidir o ejecutar necesitas tu llave de seguridad registrada y avalada. ",
      h("a", { href: "#/llaves" }, "Ir a Mi llave"));
  }

  function render() {
    const p = r.permissions;
    const actions = h("div", { class: "row" },
      p.edit ? h("button", { onclick: () => go(`#/solicitudes/${id}/editar`) }, "Editar") : null,
      p.submit ? h("button", { class: "primary", onclick: e => act(e, `/api/requests/${id}/submit`, {}, "Enviada a aprobación") }, "Enviar a aprobación") : null,
      p.review ? h("button", { class: "primary", onclick: reviewDialog }, "Decidir") : null,
      p.execute ? h("button", { class: "primary", onclick: executeDialog }, "Ejecutar") : null,
      h("button", { onclick: async () => download(`${r.code}.sql`, await (await api.get(`/api/requests/${id}/script`, { raw: true })).text()) }, "Descargar script"),
      h("button", { onclick: e => preflight(e.currentTarget) }, "Validar contra la base"),
      p.cancel ? h("button", { class: "danger", onclick: cancelDialog }, "Cancelar solicitud") : null);
    const findings = h("div");

    async function preflight(btn) {
      await busy(btn, api.get(`/api/requests/${id}/preflight`).then(f => clear(findings, h("div", { class: "card" }, findingsView(f))))
        .catch(e => clear(findings, errorBox(e))));
    }

    const approvals = r.approvals.length ? r.approvals.map(a => h("div", { class: "row", style: { padding: "6px 0", borderBottom: "1px solid var(--border)" } },
      badge(DECISION[a.decision][0], DECISION[a.decision][1]), h("b", {}, a.approver), h("span", { class: "muted small" }, fmtDate(a.decided_at)),
      a.active ? null : badge("sin efecto"),
      a.key ? h("span", { class: "seal" }, a.signature_valid ? badge("firma válida", "ok") : badge("firma inválida", "err"), h("code", {}, a.key)) : badge("sin firma", "warn"),
      a.decision === "APPROVED" && a.active && !a.signed_current_sql ? badge("firmó otro SQL", "err") : null,
      a.comment ? h("div", { style: { width: "100%" }, class: "muted" }, a.comment) : null))
      : h("div", { class: "muted" }, "Sin decisiones todavía.");

    clear(root,
      h("div", { class: "row spread" }, h("h1", {}, `${r.code} · ${r.title}`), statusBadge(r.status)),
      actions, (p.review || p.execute) && !p.has_key ? keyMissing() : null,
      h("div", { class: "card", style: { marginTop: "12px" } }, h("div", { class: "kv" },
        "Conexión", `${r.connection.name} (${r.connection.environment})`,
        "Proyecto", r.project ? `${r.project.code} — ${r.project.name}` : "(sin proyecto)",
        "Solicitó", `${r.requested_by} · ${fmtDate(r.created_at)}`,
        "Esquemas afectados", r.target_schemas.join(", "),
        "Ticket", r.ticket_ref || "—",
        "Motivo", r.reason,
        "Propósito", r.purpose || "—",
        "Aprobaciones requeridas", String(r.required_approvals),
        "Pueden aprobar", r.eligible_reviewers.length ? r.eligible_reviewers.join(", ") + " y los DBA principales" : "Los DBA principales",
        "Huella del SQL", h("code", { class: "small" }, r.digest))),
      findings,
      h("div", { class: "card" }, h("h2", {}, "Operaciones (se ejecutan en este orden)"),
        r.operations.map(o => h("div", { class: "op" },
          h("div", { class: "head" }, h("span", { class: "seq" }, `#${o.seq}`), h("b", {}, o.type), h("span", {}, `${o.schema}.${o.object_name || ""}`),
            h("span", { class: "right" }), statusBadge(o.status)),
          h("div", { class: "body" }, h("pre", {}, o.statements.map(s => s.sql.trimEnd() + (s.plsql ? "\n/" : ";")).join("\n\n")),
            o.message && o.status !== "EXECUTED" ? h("div", { class: "alert err" }, o.message) : null)))),
      h("div", { class: "card" }, h("h2", {}, "Decisiones"), approvals),
      r.executions.length ? h("div", { class: "card" }, h("h2", {}, "Ejecuciones"), r.executions.map(e => h("div", { class: "sub" },
        h("div", { class: "row" }, statusBadge(e.status), h("b", {}, e.executed_by), badge(e.mode === "AUTO" ? "desde la app" : "manual"),
          h("span", { class: "muted small" }, fmtDate(e.started_at)),
          e.key ? h("span", { class: "seal" }, e.signature_valid ? badge("firma válida", "ok") : badge("firma inválida", "err"), h("code", {}, e.key)) : null),
        e.notes ? h("p", {}, e.notes) : null, e.log ? h("pre", {}, e.log) : null))) : null,
      commentsCard(), historyCard());
  }

  function commentsCard() {
    const ta = h("textarea", { placeholder: "Escribe un comentario", maxlength: 4000 });
    const btn = h("button", { onclick: () => busy(btn, api.post(`/api/requests/${id}/comments`, { body: ta.value }).then(d => { r = { ...d, permissions: r.permissions, eligible_reviewers: r.eligible_reviewers }; render(); })
      .catch(e => toast(e.message, "err"))) }, "Comentar");
    return h("div", { class: "card" }, h("h2", {}, "Comentarios"),
      r.comments.map(c => h("div", { style: { padding: "6px 0", borderBottom: "1px solid var(--border)" } }, h("b", {}, c.author), " ", h("span", { class: "muted small" }, fmtDate(c.created_at)), h("div", {}, c.body))),
      h("div", { class: "stack", style: { marginTop: "8px" } }, ta, h("div", {}, btn)));
  }

  function historyCard() {
    const box = h("div");
    const btn = h("button", { class: "link", onclick: () => busy(btn, api.get(`/api/requests/${id}/history`).then(rows => clear(box,
      table([["Fecha", x => fmtDate(x.at)], ["Usuario", x => x.username], ["Acción", x => h("code", {}, x.action)],
        ["Detalle", x => x.details ? h("code", { class: "small" }, JSON.stringify(x.details)) : ""]], rows)))) }, "Ver historial completo");
    return h("div", { class: "card" }, h("h2", {}, "Historial"), btn, box);
  }

  async function act(e, path, body, msg) {
    await busy(e.currentTarget, api.post(path, body).then(() => { toast(msg); return load(); }).catch(err => toast(err.message, "err")));
  }

  function reviewDialog() {
    let decision = "APPROVED";
    const comment = h("textarea", { placeholder: "Comentario (obligatorio para rechazar o pedir cambios)", maxlength: 4000 });
    const opts = h("div", { class: "row" }, [["APPROVED", "Aprobar"], ["CHANGES_REQUESTED", "Pedir cambios"], ["REJECTED", "Rechazar"]].map(([v, l]) => {
      const i = h("input", { type: "radio", name: "dec", value: v, checked: v === decision });
      i.addEventListener("change", () => decision = v);
      return h("label", { class: "chk" }, i, l);
    }));
    const err = h("div");
    const next = h("button", { class: "primary" }, "Continuar y firmar");
    const m = modal("Decidir sobre " + r.code, h("div", { class: "stack" },
      h("p", {}, "Revisa el SQL de cada operación. Tu decisión queda firmada con tu llave sobre la huella exacta del SQL y la base destino."),
      opts, comment, err), [next]);
    next.addEventListener("click", () => {
      m.close();
      const c = comment.value.trim() || null;
      signFlow("Firmar decisión",
        () => api.post(`/api/requests/${id}/challenge`, { purpose: "approval", decision, comment: c }),
        (token, assertion) => api.post(`/api/requests/${id}/review`, { decision, comment: c, token, assertion }))
        .then(() => { toast("Decisión registrada y firmada"); load(); })
        .catch(e => { if (e.message !== "cancelado") toast(e.message, "err"); });
    });
  }

  function executeDialog() {
    const p = r.permissions;
    const code = h("input", { placeholder: r.code });
    const notes = h("textarea", { placeholder: "Cómo y dónde se ejecutó (obligatorio para la ejecución manual)", maxlength: 4000 });
    const auto = p.auto_execute && p.self_execution;
    const autoBtn = h("button", { class: "primary", disabled: !auto }, "Ejecutar desde la app");
    const manBtn = h("button", {}, "Registrar ejecución manual");
    const m = modal("Ejecutar " + r.code, h("div", { class: "stack" },
      h("p", {}, "Se ejecuta exactamente lo aprobado, en orden, y se detiene al primer error. Antes se verifican las firmas de las aprobaciones."),
      !p.auto_execute ? h("div", { class: "alert info" }, "Esta conexión no permite ejecutar desde la app: descarga el script, ejecútalo y registra la ejecución manual.") : null,
      p.auto_execute && !p.self_execution ? h("div", { class: "alert warn" }, "No tienes activada la ejecución desde la app. Actívala tú mismo con tu llave en ", h("a", { href: "#/llaves" }, "Mi llave"), ".") : null,
      field(`Para confirmar escribe ${r.code}`, code, { required: true }), field("Notas (ejecución manual)", notes)), [manBtn, autoBtn]);
    autoBtn.addEventListener("click", () => {
      m.close();
      signFlow("Firmar ejecución",
        () => api.post(`/api/requests/${id}/challenge`, { purpose: "execution", mode: "AUTO" }),
        (token, assertion) => api.post(`/api/requests/${id}/execute`, { confirm_code: code.value, token, assertion }))
        .then(res => { showResult(res); load(); })
        .catch(e => { if (e.message !== "cancelado") toast(e.message, "err"); load(); });
    });
    manBtn.addEventListener("click", () => {
      m.close();
      const n = notes.value;
      signFlow("Firmar ejecución manual",
        () => api.post(`/api/requests/${id}/challenge`, { purpose: "execution", mode: "MANUAL", notes: n }),
        (token, assertion) => api.post(`/api/requests/${id}/mark-executed`, { confirm_code: code.value, notes: n, token, assertion }))
        .then(() => { toast("Ejecución registrada"); load(); })
        .catch(e => { if (e.message !== "cancelado") toast(e.message, "err"); });
    });
  }

  function showResult(res) {
    modal("Resultado: " + (res.status === "EXECUTED" ? "ejecutada" : "falló"), h("div", { class: "stack" },
      res.secrets?.length ? h("div", { class: "alert warn" }, h("b", {}, "Contraseñas generadas (se muestran solo esta vez y no se guardan):"),
        h("ul", {}, res.secrets.map(s => h("li", {}, `${s.user}: `, h("code", {}, s.password))))) : null,
      h("pre", {}, res.log || "(sin registro)")), [], { sticky: !!res.secrets?.length });
  }

  function cancelDialog() {
    const c = h("textarea", { placeholder: "Motivo (opcional)" });
    const btn = h("button", { class: "danger solid" }, "Cancelar solicitud");
    const m = modal("Cancelar " + r.code, c, [btn]);
    btn.addEventListener("click", () => busy(btn, api.post(`/api/requests/${id}/cancel`, { comment: c.value }).then(() => { m.close(); toast("Solicitud cancelada"); load(); })
      .catch(e => toast(e.message, "err"))));
  }

  await load();
  return root;
}
