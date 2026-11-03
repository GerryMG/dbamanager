import { api, ApiError } from "./api.js";
import { h, clear, fmtDate } from "./ui.js";
import { explorerPage } from "./pages/explorer.js";
import { requestsPage } from "./pages/requests.js";
import { editorPage } from "./pages/editor.js";
import { detailPage } from "./pages/detail.js";
import { keysPage } from "./pages/keys.js";
import { adminPage } from "./pages/admin.js";
import { auditPage } from "./pages/audit.js";

export const state = { me: null };

const ROUTES = [
  [/^#\/explorar$/, explorerPage, "explorar"],
  [/^#\/solicitudes$/, requestsPage, "solicitudes"],
  [/^#\/solicitudes\/nueva$/, () => editorPage(null), "nueva"],
  [/^#\/solicitudes\/(\d+)\/editar$/, m => editorPage(+m[1]), "solicitudes"],
  [/^#\/solicitudes\/(\d+)$/, m => detailPage(+m[1]), "solicitudes"],
  [/^#\/llaves$/, keysPage, "llaves"],
  [/^#\/admin$/, adminPage, "admin"],
  [/^#\/bitacora$/, auditPage, "bitacora"],
];

let mainEl, navEl, bannerEl;

export function go(hash) {
  if (location.hash === hash) render();
  else location.hash = hash;
}

async function render() {
  const hash = location.hash || "#/solicitudes";
  for (const [re, page, key] of ROUTES) {
    const m = hash.match(re);
    if (!m) continue;
    for (const a of navEl.querySelectorAll("a")) a.classList.toggle("active", a.dataset.key === key);
    document.querySelector(".side")?.classList.remove("open");
    clear(mainEl, h("div", { class: "muted" }, "Cargando…"));
    try {
      const node = await page(m);
      clear(mainEl, node);
    } catch (e) {
      clear(mainEl, h("div", { class: "alert err" }, e.message || String(e)));
    }
    window.scrollTo(0, 0);
    return;
  }
  location.hash = "#/solicitudes";
}

export async function refreshMe() {
  state.me = await api.get("/api/me");
  renderBanner();
  return state.me;
}

function renderBanner() {
  const r = state.me?.root_reset;
  if (!bannerEl) return;
  if (r && (state.me.is_dba || state.me.is_admin)) {
    clear(bannerEl, `Restablecimiento de la llave raíz en espera, solicitado por ${r.requested_by}. Se aplicará el ${fmtDate(r.effective_at)} `
      + "si un DBA principal no lo cancela con su llave. ", h("a", { href: "#/llaves" }, "Ver detalle"));
    bannerEl.classList.remove("hidden");
  } else bannerEl.classList.add("hidden");
}

async function start() {
  const root = document.getElementById("app");
  try {
    await refreshMe();
  } catch (e) {
    if (e instanceof ApiError && e.status === 403) {
      clear(root, h("div", { class: "card", style: { maxWidth: "560px", margin: "10vh auto" } },
        h("h1", {}, "Sin acceso a DBA Manager"), h("p", {}, e.message),
        h("p", {}, h("a", { href: "/logout" }, "Cerrar sesión"))));
      return;
    }
    clear(root, h("div", { class: "alert err", style: { margin: "40px" } }, "No se pudo cargar DBA Manager: " + (e.message || e)));
    return;
  }
  const me = state.me;
  const links = [
    ["#/solicitudes", "Solicitudes", "solicitudes"],
    ["#/solicitudes/nueva", "Nueva solicitud", "nueva"],
    ["#/explorar", "Explorar estructura", "explorar"],
    me.is_dba ? ["#/llaves", "Mi llave", "llaves"] : null,
    me.is_admin || me.is_dba ? ["#/admin", "Administración", "admin"] : null,
    me.is_admin || me.is_dba ? ["#/bitacora", "Bitácora", "bitacora"] : null,
  ].filter(Boolean);
  navEl = h("nav", { class: "nav" }, links.map(([href, label, key]) => h("a", { href, "data-key": key }, label)));
  const side = h("aside", { class: "side" },
    h("div", { class: "brand" }, h("img", { src: "/static/favicon.svg", alt: "" }), "DBA Manager"),
    navEl, h("div", { class: "grow" }),
    h("div", { class: "who" }, h("b", {}, me.full_name || me.username), me.username, h("br"),
      h("span", {}, me.roles.join(", ")), h("br"), h("a", { href: "/logout" }, "Cerrar sesión")));
  mainEl = h("main", { class: "main" });
  bannerEl = h("div", { class: "banner hidden" });
  const topbar = h("div", { class: "topbar" }, h("button", { onclick: () => side.classList.toggle("open") }, "☰"), h("b", {}, "DBA Manager"));
  clear(root, h("div", { class: "shell" }, side, h("div", {}, topbar, bannerEl, mainEl)));
  renderBanner();
  window.addEventListener("hashchange", render);
  render();
}

start();
