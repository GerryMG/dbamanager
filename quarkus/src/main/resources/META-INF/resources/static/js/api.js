// Cliente de la API. La sesión viaja en una cookie segura (la gestiona el servidor); el navegador
// nunca ve los tokens de Keycloak.

export class ApiError extends Error {
  constructor(status, detail) {
    super(typeof detail === "string" ? detail : detail?.message || `Error ${status}`);
    this.status = status;
    this.detail = detail;
    this.code = detail && typeof detail === "object" ? detail.code : undefined;
  }
}

async function request(method, path, body, opts = {}) {
  const headers = { "X-DBAM": "1", "X-Requested-With": "JavaScript", Accept: "application/json" };
  let payload = body;
  if (body !== undefined && !(body instanceof FormData)) {
    headers["Content-Type"] = "application/json";
    payload = JSON.stringify(body);
  }
  const res = await fetch(path, { method, headers, body: payload, credentials: "same-origin", redirect: "manual" });
  if (res.status === 499 || res.type === "opaqueredirect" || res.status === 401 && !(await peekCode(res))) {
    // La sesión venció: se recarga la página para volver a iniciar sesión
    location.reload();
    return new Promise(() => {});
  }
  if (opts.raw) {
    if (!res.ok) throw new ApiError(res.status, (await safeJson(res))?.detail);
    return res;
  }
  const data = res.status === 204 ? null : await safeJson(res);
  if (!res.ok) throw new ApiError(res.status, data?.detail ?? res.statusText);
  return data;
}

async function peekCode(res) {
  try {
    const d = await res.clone().json();
    return d?.detail?.code;
  } catch {
    return undefined;
  }
}

async function safeJson(res) {
  const text = await res.text();
  if (!text) return null;
  try {
    return JSON.parse(text);
  } catch {
    return { detail: text };
  }
}

export const api = {
  get: (p, o) => request("GET", p, undefined, o),
  post: (p, b, o) => request("POST", p, b ?? {}, o),
  put: (p, b) => request("PUT", p, b ?? {}),
  del: p => request("DELETE", p),
  upload: (p, form) => request("POST", p, form),
};

let metaCache;
export async function meta() {
  return (metaCache ||= api.get("/api/meta"));
}
