// Utilidades de interfaz. Todo el contenido se inserta como texto (nunca como HTML).

export function h(tag, attrs, ...children) {
  const el = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs || {})) {
    if (v === null || v === undefined || v === false) continue;
    if (k === "class") el.className = v;
    else if (k === "style" && typeof v === "object") Object.assign(el.style, v);
    else if (k.startsWith("on") && typeof v === "function") el.addEventListener(k.slice(2), v);
    else if (k === "value") el.value = v;
    else if (k === "checked") el.checked = !!v;
    else if (v === true) el.setAttribute(k, "");
    else el.setAttribute(k, v);
  }
  append(el, children);
  return el;
}

function append(el, children) {
  for (const c of children.flat(Infinity)) {
    if (c === null || c === undefined || c === false) continue;
    el.append(c instanceof Node ? c : document.createTextNode(String(c)));
  }
}

export function clear(el, ...children) {
  el.replaceChildren();
  append(el, children);
  return el;
}

export function fmtDate(v) {
  if (!v) return "";
  const d = new Date(v);
  if (isNaN(d)) return String(v);
  return d.toLocaleString("es", { dateStyle: "short", timeStyle: "short" });
}

const STATUS = {
  DRAFT: ["Borrador", ""], PENDING_APPROVAL: ["Pendiente de aprobación", "warn"], CHANGES_REQUESTED: ["Cambios solicitados", "warn"],
  APPROVED: ["Aprobada", "info"], REJECTED: ["Rechazada", "err"], CANCELLED: ["Cancelada", ""], EXECUTING: ["Ejecutando", "info"],
  EXECUTED: ["Ejecutada", "ok"], FAILED: ["Falló", "err"], PENDING: ["Pendiente", ""], ACTIVE: ["Activa", "ok"], REVOKED: ["Revocada", "err"],
  SUCCESS: ["Correcta", "ok"], WAITING: ["En espera", "warn"],
};

export function statusBadge(s) {
  const [label, kind] = STATUS[s] || [s, ""];
  return h("span", { class: "badge " + kind }, label);
}

export function badge(text, kind = "") {
  return h("span", { class: "badge " + kind }, text);
}

// ------------------------------------------------------------------ avisos
let toastBox;
export function toast(msg, kind = "ok", ms = 4500) {
  toastBox ||= document.body.appendChild(h("div", { class: "toasts" }));
  const t = h("div", { class: "toast " + kind }, msg);
  toastBox.append(t);
  setTimeout(() => t.remove(), ms);
}

export function errorBox(err) {
  if (!err) return null;
  const detail = err.detail ?? err.message ?? String(err);
  if (detail && typeof detail === "object" && Array.isArray(detail.errors)) {
    return h("div", { class: "alert err" }, detail.message || "Hay errores",
      h("ul", {}, detail.errors.map(e => h("li", {}, (e.op_seq ? `#${e.op_seq} ` : "") + (e.field && e.field !== "operación" ? `${e.field}: ` : "") + e.message))));
  }
  return h("div", { class: "alert err" }, typeof detail === "object" ? (detail.message || JSON.stringify(detail)) : detail);
}

export function errorText(err) {
  const d = err?.detail ?? err?.message ?? String(err);
  if (d && typeof d === "object") return d.message || JSON.stringify(d);
  return d;
}

// ------------------------------------------------------------------ ventanas
export function modal(title, body, actions = [], opts = {}) {
  const back = h("div", { class: "modal-back" });
  const close = () => { back.remove(); document.removeEventListener("keydown", onKey); opts.onClose?.(); };
  const onKey = e => { if (e.key === "Escape" && !opts.sticky) close(); };
  document.addEventListener("keydown", onKey);
  const foot = h("div", { class: "mf" });
  for (const a of actions) foot.append(a);
  back.append(h("div", { class: "modal" + (opts.wide ? " wide" : "") },
    h("div", { class: "mh" }, title, h("span", { class: "right" }), h("button", { class: "link", onclick: close, "aria-label": "Cerrar" }, "✕")),
    h("div", { class: "mb" }, body), actions.length ? foot : null));
  if (!opts.sticky) back.addEventListener("mousedown", e => { if (e.target === back) close(); });
  document.body.append(back);
  return { close, back };
}

export function confirmDialog(title, text, okLabel = "Confirmar", danger = false) {
  return new Promise(resolve => {
    let done = false;
    const ok = h("button", { class: danger ? "danger solid" : "primary", onclick: () => { done = true; m.close(); resolve(true); } }, okLabel);
    const m = modal(title, h("p", {}, text), [h("button", { onclick: () => m.close() }, "Cancelar"), ok],
      { onClose: () => { if (!done) resolve(false); } });
  });
}

// ------------------------------------------------------------------ formularios
export function field(label, input, opts = {}) {
  return h("label", { class: "f" + (opts.class ? " " + opts.class : "") }, h("span", { class: opts.required ? "req" : "" }, label), input,
    opts.hint ? h("span", { class: "small muted" }, opts.hint) : null);
}

export function select(options, value, onchange, attrs = {}) {
  const s = h("select", attrs, options.map(o => {
    const [v, l] = Array.isArray(o) ? o : [o, o];
    return h("option", { value: v ?? "" }, l);
  }));
  s.value = value ?? "";
  if (onchange) s.addEventListener("change", () => onchange(s.value));
  return s;
}

export function check(label, value, onchange) {
  const i = h("input", { type: "checkbox", checked: !!value });
  i.addEventListener("change", () => onchange(i.checked));
  return h("label", { class: "chk" }, i, label);
}

export function busy(button, promise) {
  const old = [...button.childNodes];
  button.disabled = true;
  button.replaceChildren(h("span", { class: "spinner" }), " ", ...old);
  return promise.finally(() => { button.disabled = false; button.replaceChildren(...old); });
}

export function table(columns, rows, opts = {}) {
  if (!rows?.length) return h("div", { class: "empty" }, opts.empty || "Sin elementos");
  return h("div", { class: "tablewrap" }, h("table", {},
    h("thead", {}, h("tr", {}, columns.map(c => h("th", {}, c[0])))),
    h("tbody", {}, rows.map(r => h("tr", { class: opts.onclick ? "click" : "", onclick: opts.onclick ? () => opts.onclick(r) : null },
      columns.map(c => h("td", {}, c[1](r))))))));
}

export function download(name, text, type = "text/plain") {
  const url = URL.createObjectURL(new Blob([text], { type }));
  const a = h("a", { href: url, download: name });
  document.body.append(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
