import { api, ApiError } from "../api.js";
import { h, clear, table, statusBadge, badge, fmtDate, modal, busy, errorBox, toast, field } from "../ui.js";
import { state, refreshMe } from "../app.js";
import { supported, unsupportedReason, createCredential, signFlow } from "../webauthn.js";

export async function keysPage() {
  const root = h("div");

  async function load() {
    const [s] = await Promise.all([api.get("/api/keys"), refreshMe()]);
    render(s);
  }

  function render(s) {
    const me = state.me;
    const mine = s.mine;
    const trusted = mine.some(k => k.trusted);
    clear(root,
      h("h1", {}, "Mi llave de seguridad"),
      h("p", { class: "muted" }, "Tus decisiones (aprobar, rechazar, ejecutar, otorgar alcances) se firman con tu llave: una llave USB, la huella o el rostro de tu equipo, o tu teléfono. ",
        "La llave privada nunca sale de tu dispositivo, así que nadie más puede decidir en tu nombre."),
      !supported() ? h("div", { class: "alert err" }, unsupportedReason()) : null,
      s.reset ? resetCard(s.reset, me) : null,
      h("div", { class: "card" }, h("h2", {}, "Llave raíz"),
        s.root ? h("div", { class: "kv" }, "DBA principal", s.root.username, "Huella", h("code", {}, s.root.fingerprint), "Desde", fmtDate(s.root.created_at))
          : h("div", { class: "alert warn" }, s.root_slot_open
            ? "Aún no hay llave raíz. El primer DBA principal (dba_admin) que registre su llave será la raíz: avalará las llaves de los demás DBAs."
            : "No hay una llave raíz vigente."),
        h("p", { class: "small muted" }, "Verifica que la huella de la raíz sea la de tu llave. Si alguna vez no lo es, nadie debe aprobar nada hasta aclararlo.")),
      h("div", { class: "card" }, h("div", { class: "row spread" }, h("h2", {}, "Mis llaves"),
        me.is_dba ? h("button", { class: "primary", onclick: () => registerFlow(s) }, mine.length ? "Agregar otra llave" : "Registrar mi llave") : null),
        !trusted && mine.some(k => k.status === "PENDING") ? h("div", { class: "alert info" },
          me.is_dba_admin && s.root_slot_open ? "" : "Tu llave espera el aval de un DBA principal. Pídele que la revise en esta misma pantalla.") : null,
        keysTable(mine, s, true),
        h("p", { class: "small muted" }, "Recomendado: registra dos llaves (por ejemplo, la del equipo y una USB) por si pierdes una. La segunda se avala con la primera.")),
      me.is_dba ? executionCard(me, trusted) : null,
      s.pending.length ? h("div", { class: "card" }, h("h2", {}, "Llaves de otros DBAs esperando tu aval"),
        h("p", { class: "small muted" }, "Confirma con la persona, por un medio aparte, que la huella coincide con la que ve en su pantalla antes de avalar."),
        keysTable(s.pending, s, false)) : null,
      s.all.length ? h("div", { class: "card" }, h("h2", {}, "Todas las llaves"), table([
        ["DBA", k => k.username], ["Nombre", k => k.label], ["Huella", k => h("code", {}, k.fingerprint)],
        ["Estado", k => [statusBadge(k.status), k.root ? badge("raíz", "info") : null, k.status === "ACTIVE" && !k.trusted ? badge("cadena inválida", "err") : null]],
        ["Avaló", k => k.endorsed_by || (k.root ? "—" : "")], ["Último uso", k => fmtDate(k.last_used_at)]], s.all)) : null,
      (me.is_admin || me.is_dba_admin) && !s.reset && s.root ? h("div", { class: "card" }, h("h2", {}, "Restablecer la llave raíz"),
        h("p", { class: "small muted" }, "Solo si el DBA principal perdió todas sus llaves. El restablecimiento espera 72 horas, se avisa a todos los DBAs ",
          "y cualquier DBA principal con su llave puede cancelarlo. Al aplicarse se revocan todas las llaves y lo aprobado sin ejecutar debe aprobarse de nuevo."),
        h("button", { class: "danger", onclick: resetDialog }, "Solicitar restablecimiento")) : null);
  }

  function keysTable(keys, s, own) {
    return table([
      ["Nombre", k => own ? k.label : `${k.username} · ${k.label}`],
      ["Huella", k => h("code", {}, k.fingerprint)],
      ["Estado", k => [statusBadge(k.status), k.root ? badge("raíz", "info") : null, k.status === "ACTIVE" && !k.trusted ? badge("cadena inválida", "err") : null]],
      ["Registrada", k => fmtDate(k.created_at)],
      ["Avaló", k => k.endorsed_by || (k.root ? "—" : "")],
      ["Último uso", k => fmtDate(k.last_used_at)],
      ["", k => h("div", { class: "row" },
        k.status === "PENDING" && (state.me.has_key || !own) ? h("button", { class: "small ok", onclick: () => endorse(k) }, own ? "Avalar con mi otra llave" : "Avalar") : null,
        k.status !== "REVOKED" && !k.root ? h("button", { class: "small danger", onclick: () => revoke(k, own) }, "Revocar") : null)],
    ], keys, { empty: own ? "Aún no registras ninguna llave" : "Nada pendiente" });
  }

  async function registerFlow(s) {
    if (!supported()) return toast(unsupportedReason(), "err");
    if (!s.reauth_ok) {
      modal("Confirma tu identidad", h("div", { class: "stack" },
        h("p", {}, `Para registrar una llave vuelve a escribir tu contraseña. Tendrás ${s.reauth_minutes} minutos para completar el registro.`)),
        [h("button", { class: "primary", onclick: () => { location.href = "/reauth"; } }, "Escribir mi contraseña")]);
      return;
    }
    const label = h("input", { placeholder: "p. ej. Laptop, YubiKey, Teléfono", maxlength: 100 });
    const err = h("div");
    const btn = h("button", { class: "primary" }, "Registrar con mi dispositivo");
    const m = modal("Registrar llave", h("div", { class: "stack" }, field("Nombre de la llave", label), err,
      h("p", { class: "small muted" }, "El navegador te pedirá tocar tu llave o usar la huella, el rostro o el PIN del dispositivo.")), [btn]);
    btn.addEventListener("click", () => busy(btn, (async () => {
      const o = await api.post("/api/keys/register/options");
      let credential;
      try {
        credential = await createCredential(o);
      } catch (e) {
        throw new Error(e?.name === "NotAllowedError" ? "Se canceló el registro o se agotó el tiempo" : e?.name === "InvalidStateError" ? "Esa llave ya está registrada" : e.message);
      }
      const k = await api.post("/api/keys/register", { token: o.token, label: label.value, credential });
      m.close();
      if (k.root) toast("Llave raíz registrada. Ya puedes avalar las llaves de los demás DBAs.");
      else if (k.status === "PENDING" && state.me.has_key) {
        toast("Llave registrada; ahora aválala con tu otra llave");
        await endorse(k);
      } else toast("Llave registrada; espera el aval de un DBA principal");
      load();
    })()).catch(e => {
      if (e instanceof ApiError && e.code === "REAUTH_REQUIRED") { m.close(); location.href = "/reauth"; return; }
      err.replaceChildren(errorBox(e));
    }));
  }

  function endorse(k) {
    return signFlow("Avalar llave",
      () => api.post("/api/keys/challenge", { purpose: "endorse_key", key_id: k.id }),
      (token, assertion) => api.post(`/api/keys/${k.id}/endorse`, { token, assertion }))
      .then(() => { toast("Llave avalada"); load(); })
      .catch(e => { if (e.message !== "cancelado") toast(e.message, "err"); });
  }

  async function revoke(k, own) {
    const needsSig = !own || (k.status === "ACTIVE" && state.me.has_key);
    if (!needsSig) {
      await api.post(`/api/keys/${k.id}/revoke`, {}).then(() => { toast("Llave revocada"); load(); }).catch(e => toast(e.message, "err"));
      return;
    }
    signFlow("Revocar llave",
      () => api.post("/api/keys/challenge", { purpose: "revoke_key", key_id: k.id }),
      (token, assertion) => api.post(`/api/keys/${k.id}/revoke`, { token, assertion }))
      .then(() => { toast("Llave revocada"); load(); })
      .catch(e => { if (e.message !== "cancelado") toast(e.message, "err"); });
  }

  function executionCard(me, trusted) {
    const until = me.can_execute_until ? ` hasta ${fmtDate(me.can_execute_until)}` : "";
    return h("div", { class: "card" }, h("h2", {}, "Ejecución desde DBA Manager"),
      h("p", { class: "muted" }, "Permite que la app ejecute, con la cuenta ejecutora, las solicitudes aprobadas que tú ejecutes. Solo tú puedes activarla para ti, con tu llave, y se desactiva sola."),
      me.can_execute ? h("div", { class: "row" }, badge("Activa" + until, "ok"),
        h("button", { onclick: e => busy(e.currentTarget, api.put("/api/me/execution", { enabled: false }).then(() => { toast("Ejecución desactivada"); load(); })) }, "Desactivar"))
        : h("div", { class: "row" }, badge("Desactivada"),
          h("button", { class: "primary", disabled: !trusted, onclick: () => signFlow("Activar ejecución",
            () => api.post("/api/keys/challenge", { purpose: "self_execution" }),
            (token, assertion) => api.put("/api/me/execution", { enabled: true, confirm: "ACTIVAR", token, assertion }))
            .then(() => { toast("Ejecución activada"); load(); }).catch(e => { if (e.message !== "cancelado") toast(e.message, "err"); }) }, "Activar con mi llave"),
          !trusted ? h("span", { class: "small muted" }, "Requiere una llave avalada") : null));
  }

  function resetCard(r, me) {
    return h("div", { class: "card", style: { borderColor: "var(--err)" } }, h("h2", {}, "Restablecimiento de la llave raíz en espera"),
      h("div", { class: "kv" }, "Solicitó", r.requested_by, "Motivo", r.reason, "Se aplica", fmtDate(r.effective_at)),
      h("p", {}, "Si no lo reconoces, cancélalo con tu llave. Al aplicarse se revocan todas las llaves."),
      me.is_dba_admin && me.has_key ? h("button", { class: "danger solid", onclick: () => signFlow("Cancelar restablecimiento",
        () => api.post("/api/keys/challenge", { purpose: "cancel_root_reset" }),
        (token, assertion) => api.post("/api/keys/root-reset/cancel", { token, assertion }))
        .then(() => { toast("Restablecimiento cancelado"); load(); }).catch(e => { if (e.message !== "cancelado") toast(e.message, "err"); }) }, "Cancelar con mi llave") : null);
  }

  function resetDialog() {
    const reason = h("textarea", { placeholder: "Por qué se necesita (mínimo 10 caracteres)", maxlength: 2000 });
    const btn = h("button", { class: "danger solid" }, "Solicitar");
    const m = modal("Solicitar restablecimiento de la raíz", h("div", { class: "stack" }, reason), [btn]);
    btn.addEventListener("click", () => busy(btn, api.post("/api/keys/root-reset", { reason: reason.value })
      .then(() => { m.close(); toast("Restablecimiento solicitado: se aplicará en 72 horas si nadie lo cancela"); load(); })
      .catch(e => toast(e.message, "err"))));
  }

  await load();
  return root;
}
