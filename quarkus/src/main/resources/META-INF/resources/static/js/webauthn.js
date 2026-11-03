// Llave de seguridad (WebAuthn / FIDO2): registro y firma. La llave privada nunca sale del
// dispositivo; aquí solo viajan la llave pública y las firmas.
import { h, modal, busy, errorBox } from "./ui.js";

const enc = buf => btoa(String.fromCharCode(...new Uint8Array(buf))).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
const dec = s => Uint8Array.from(atob(s.replace(/-/g, "+").replace(/_/g, "/") + "===".slice((s.length + 3) % 4)), c => c.charCodeAt(0));

export function supported() {
  return !!(window.PublicKeyCredential && navigator.credentials && window.isSecureContext);
}

export function unsupportedReason() {
  if (!window.isSecureContext) return "Las llaves de seguridad solo funcionan en HTTPS (o en localhost). Abre DBA Manager por su dirección HTTPS.";
  return "Este navegador no soporta llaves de seguridad (WebAuthn).";
}

export async function createCredential(o) {
  const cred = await navigator.credentials.create({
    publicKey: {
      rp: { id: o.rp_id, name: "DBA Manager" },
      user: { id: dec(o.user_id), name: o.user_name, displayName: o.display_name },
      challenge: dec(o.challenge),
      pubKeyCredParams: [{ type: "public-key", alg: -7 }, { type: "public-key", alg: -8 }, { type: "public-key", alg: -257 }],
      authenticatorSelection: { residentKey: "preferred", userVerification: "required" },
      attestation: "none",
      excludeCredentials: (o.exclude_credentials || []).map(id => ({ type: "public-key", id: dec(id) })),
      timeout: o.timeout,
    },
  });
  return {
    id: cred.id, type: cred.type,
    response: { clientDataJSON: enc(cred.response.clientDataJSON), attestationObject: enc(cred.response.attestationObject) },
  };
}

export async function getAssertion(c) {
  const cred = await navigator.credentials.get({
    publicKey: {
      challenge: dec(c.challenge),
      rpId: c.rp_id,
      allowCredentials: (c.allow_credentials || []).map(id => ({ type: "public-key", id: dec(id) })),
      userVerification: "required",
      timeout: c.timeout,
    },
  });
  return {
    id: cred.id, type: cred.type,
    response: {
      authenticatorData: enc(cred.response.authenticatorData),
      clientDataJSON: enc(cred.response.clientDataJSON),
      signature: enc(cred.response.signature),
      userHandle: cred.response.userHandle ? enc(cred.response.userHandle) : null,
    },
  };
}

const LABELS = {
  approval: "Decisión sobre una solicitud", execution: "Ejecución de una solicitud", self_execution: "Activar la ejecución desde la app",
  scope: "Otorgar alcance de aprobación", endorse_key: "Avalar una llave", revoke_key: "Revocar una llave", cancel_root_reset: "Cancelar el restablecimiento de la raíz",
};
const FIELDS = {
  code: "Solicitud", title: "Título", decision: "Decisión", approver: "Quién decide", executor: "Quién ejecuta", mode: "Modo",
  digest: "Huella del SQL y la base (SHA-256)", username: "Usuario", connection_id: "Conexión", schema: "Esquema", granted_by: "Otorga",
  endorser: "Quién avala", key_owner: "Dueño de la llave", fingerprint: "Huella de la llave", label: "Nombre de la llave", user: "Usuario",
  by: "Quién", reset: "Restablecimiento", enabled: "Activar", at: "Fecha",
};
const DECISIONS = { APPROVED: "Aprobar", REJECTED: "Rechazar", CHANGES_REQUESTED: "Pedir cambios" };

/**
 * Muestra lo que se va a firmar y pide la llave. `getChallenge` obtiene el desafío del servidor;
 * `submit(token, assertion)` envía la acción firmada. Resuelve con el resultado de submit.
 */
export function signFlow(title, getChallenge, submit) {
  return new Promise((resolve, reject) => {
    if (!supported()) {
      modal(title, h("div", { class: "alert err" }, unsupportedReason()));
      reject(new Error(unsupportedReason()));
      return;
    }
    const body = h("div", { class: "stack" }, h("div", { class: "muted" }, "Preparando lo que vas a firmar…"));
    const errBox = h("div");
    const signBtn = h("button", { class: "primary", disabled: true }, "Firmar con mi llave");
    let challenge, finished = false;
    const m = modal(title, h("div", {}, body, errBox), [h("button", { onclick: () => m.close() }, "Cancelar"), signBtn],
      { onClose: () => { if (!finished) reject(new Error("cancelado")); } });
    getChallenge().then(c => {
      challenge = c;
      const st = c.statement || {};
      body.replaceChildren(
        h("p", {}, "Tu llave de seguridad va a firmar exactamente esto. Nadie más puede firmar en tu nombre."),
        h("div", { class: "statement" }, h("div", { style: { fontWeight: 600, marginBottom: "6px" } }, LABELS[st.kind] || st.kind),
          h("div", { class: "kv" }, Object.entries(FIELDS).filter(([k]) => st[k] !== undefined && st[k] !== null).flatMap(([k, l]) => [
            h("div", {}, l), h("div", {}, k === "decision" ? DECISIONS[st[k]] || st[k] : k === "fingerprint" ? String(st[k]).slice(0, 16).toUpperCase() : String(st[k])),
          ]))),
        h("p", { class: "small muted" }, "Al presionar Firmar, el navegador te pedirá tu llave (USB, huella, rostro o PIN del dispositivo)."));
      signBtn.disabled = false;
    }).catch(e => { errBox.replaceChildren(errorBox(e)); });
    signBtn.addEventListener("click", () => {
      errBox.replaceChildren();
      busy(signBtn, (async () => {
        let assertion;
        try {
          assertion = await getAssertion(challenge);
        } catch (e) {
          throw new Error(e?.name === "NotAllowedError" ? "La llave no firmó (se canceló o se agotó el tiempo)" : e.message);
        }
        const result = await submit(challenge.token, assertion);
        finished = true;
        m.close();
        resolve(result);
      })()).catch(e => errBox.replaceChildren(errorBox(e)));
    });
  });
}
