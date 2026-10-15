/**
 * Cliente OIDC mínimo (Authorization Code + PKCE S256) para Keycloak u otro proveedor OIDC.
 *
 * No usa keycloak-js porque éste exige "secure context" (HTTPS o localhost); aquí solo se usa
 * crypto.getRandomValues (disponible también en HTTP) y SHA-256 en JS si no hay crypto.subtle.
 */

declare global {
  interface Window {
    __CONFIG__: { keycloakUrl: string; keycloakRealm: string; keycloakClient: string };
  }
}

const cfg = window.__CONFIG__;
const base = `${cfg.keycloakUrl.replace(/\/$/, "")}/realms/${cfg.keycloakRealm}/protocol/openid-connect`;
const redirectUri = `${window.location.origin}/`;
const STORE = "dbamanager.auth";
const PENDING = "dbamanager.auth.pending";

interface Tokens {
  access_token: string;
  refresh_token?: string;
  id_token?: string;
  expires_at: number;
}

export interface Claims {
  preferred_username: string;
  name?: string;
  email?: string;
  realm_access?: { roles: string[] };
}

let tokens: Tokens | null = load();
let refreshing: Promise<string> | null = null;

function load(): Tokens | null {
  try {
    const raw = sessionStorage.getItem(STORE);
    return raw ? (JSON.parse(raw) as Tokens) : null;
  } catch {
    return null;
  }
}

function save(t: Tokens | null) {
  tokens = t;
  try {
    if (t) sessionStorage.setItem(STORE, JSON.stringify(t));
    else sessionStorage.removeItem(STORE);
  } catch {
    /* almacenamiento no disponible: se mantiene solo en memoria */
  }
}

function b64url(bytes: Uint8Array): string {
  let s = "";
  bytes.forEach((b) => (s += String.fromCharCode(b)));
  return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function randomString(len = 48): string {
  return b64url(crypto.getRandomValues(new Uint8Array(len)));
}

async function sha256(text: string): Promise<Uint8Array> {
  const data = new TextEncoder().encode(text);
  if (globalThis.crypto?.subtle) return new Uint8Array(await crypto.subtle.digest("SHA-256", data));
  return sha256Js(data);
}

/** SHA-256 en JS puro (solo se usa cuando la página no es "secure context"). */
function sha256Js(msg: Uint8Array): Uint8Array {
  const K = new Uint32Array([
    0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5, 0xd807aa98,
    0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174, 0xe49b69c1, 0xefbe4786,
    0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da, 0x983e5152, 0xa831c66d, 0xb00327c8,
    0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967, 0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13,
    0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85, 0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819,
    0xd6990624, 0xf40e3585, 0x106aa070, 0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a,
    0x5b9cca4f, 0x682e6ff3, 0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7,
    0xc67178f2,
  ]);
  const H = new Uint32Array([0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19]);
  const len = msg.length;
  const padded = new Uint8Array(((len + 9 + 63) >> 6) << 6);
  padded.set(msg);
  padded[len] = 0x80;
  const view = new DataView(padded.buffer);
  view.setUint32(padded.length - 4, len * 8);
  view.setUint32(padded.length - 8, Math.floor(len / 0x20000000));
  const W = new Uint32Array(64);
  const rotr = (x: number, n: number) => (x >>> n) | (x << (32 - n));
  for (let off = 0; off < padded.length; off += 64) {
    for (let i = 0; i < 16; i++) W[i] = view.getUint32(off + i * 4);
    for (let i = 16; i < 64; i++) {
      const s0 = rotr(W[i - 15], 7) ^ rotr(W[i - 15], 18) ^ (W[i - 15] >>> 3);
      const s1 = rotr(W[i - 2], 17) ^ rotr(W[i - 2], 19) ^ (W[i - 2] >>> 10);
      W[i] = (W[i - 16] + s0 + W[i - 7] + s1) >>> 0;
    }
    let [a, b, c, d, e, f, g, h] = H;
    for (let i = 0; i < 64; i++) {
      const S1 = rotr(e, 6) ^ rotr(e, 11) ^ rotr(e, 25);
      const t1 = (h + S1 + ((e & f) ^ (~e & g)) + K[i] + W[i]) >>> 0;
      const S0 = rotr(a, 2) ^ rotr(a, 13) ^ rotr(a, 22);
      const t2 = (S0 + ((a & b) ^ (a & c) ^ (b & c))) >>> 0;
      h = g; g = f; f = e; e = (d + t1) >>> 0; d = c; c = b; b = a; a = (t1 + t2) >>> 0;
    }
    H[0] += a; H[1] += b; H[2] += c; H[3] += d; H[4] += e; H[5] += f; H[6] += g; H[7] += h;
  }
  const out = new Uint8Array(32);
  const ov = new DataView(out.buffer);
  H.forEach((v, i) => ov.setUint32(i * 4, v));
  return out;
}

async function tokenRequest(params: Record<string, string>): Promise<Tokens> {
  const res = await fetch(`${base}/token`, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({ client_id: cfg.keycloakClient, ...params }),
  });
  if (!res.ok) throw new Error(`Error de autenticación (${res.status})`);
  const t = await res.json();
  return {
    access_token: t.access_token,
    refresh_token: t.refresh_token,
    id_token: t.id_token ?? tokens?.id_token,
    expires_at: Date.now() + t.expires_in * 1000,
  };
}

export async function login(): Promise<never> {
  const state = randomString(24);
  const verifier = randomString(48);
  const challenge = b64url(await sha256(verifier));
  sessionStorage.setItem(PENDING, JSON.stringify({ state, verifier, path: location.pathname + location.search }));
  const url = new URL(`${base}/auth`);
  url.search = new URLSearchParams({
    client_id: cfg.keycloakClient,
    redirect_uri: redirectUri,
    response_type: "code",
    scope: "openid profile email",
    state,
    code_challenge: challenge,
    code_challenge_method: "S256",
  }).toString();
  window.location.assign(url.toString());
  return new Promise<never>(() => {});
}

/** Procesa el regreso de Keycloak o inicia sesión. Devuelve la ruta a la que hay que volver. */
export async function initAuth(): Promise<string | null> {
  const params = new URLSearchParams(location.search);
  const code = params.get("code");
  const pendingRaw = sessionStorage.getItem(PENDING);
  if (code && pendingRaw) {
    const pending = JSON.parse(pendingRaw);
    sessionStorage.removeItem(PENDING);
    if (params.get("state") !== pending.state) throw new Error("Estado OIDC inválido");
    save(await tokenRequest({ grant_type: "authorization_code", code, redirect_uri: redirectUri, code_verifier: pending.verifier }));
    window.history.replaceState(null, "", pending.path || "/");
    return pending.path || "/";
  }
  if (!tokens) await login();
  return null;
}

export async function getToken(): Promise<string> {
  if (!tokens) return login();
  if (tokens.expires_at - Date.now() > 30_000) return tokens.access_token;
  if (!tokens.refresh_token) return login();
  refreshing ??= tokenRequest({ grant_type: "refresh_token", refresh_token: tokens.refresh_token })
    .then((t) => {
      save(t);
      return t.access_token;
    })
    .catch(() => {
      save(null);
      return login();
    })
    .finally(() => {
      refreshing = null;
    });
  return refreshing;
}

export function claims(): Claims | null {
  if (!tokens) return null;
  const payload = tokens.access_token.split(".")[1].replace(/-/g, "+").replace(/_/g, "/");
  const json = decodeURIComponent(
    atob(payload)
      .split("")
      .map((c) => "%" + c.charCodeAt(0).toString(16).padStart(2, "0"))
      .join(""),
  );
  return JSON.parse(json);
}

export function logout() {
  const idToken = tokens?.id_token;
  save(null);
  const url = new URL(`${base}/logout`);
  url.search = new URLSearchParams({
    client_id: cfg.keycloakClient,
    post_logout_redirect_uri: redirectUri,
    ...(idToken ? { id_token_hint: idToken } : {}),
  }).toString();
  window.location.assign(url.toString());
}
