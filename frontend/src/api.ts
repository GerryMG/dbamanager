import { useQuery } from "@tanstack/react-query";
import { getToken } from "./auth";

export class ApiError extends Error {
  constructor(public status: number, message: string, public details?: FieldError[]) {
    super(message);
  }
}

export interface FieldError {
  op_seq: number;
  field: string;
  message: string;
}

async function request<T>(method: string, path: string, body?: unknown, raw = false): Promise<T> {
  const isForm = body instanceof FormData;
  const res = await fetch(path, {
    method,
    headers: {
      Authorization: `Bearer ${await getToken()}`,
      ...(body !== undefined && !isForm ? { "Content-Type": "application/json" } : {}),
    },
    body: body === undefined ? undefined : isForm ? body : JSON.stringify(body),
  });
  if (!res.ok) {
    let message = `Error ${res.status}`;
    let details: FieldError[] | undefined;
    try {
      const data = await res.json();
      const d = data.detail;
      if (typeof d === "string") message = d;
      else if (d?.message) {
        message = d.message;
        details = d.errors;
      } else if (Array.isArray(d)) {
        message = d.map((e: { loc: string[]; msg: string }) => `${e.loc.slice(1).join(".")}: ${e.msg}`).join("; ");
      }
    } catch {
      /* respuesta sin JSON */
    }
    throw new ApiError(res.status, message, details);
  }
  return (raw ? res.text() : res.json()) as Promise<T>;
}

export const api = {
  get: <T,>(p: string) => request<T>("GET", p),
  text: (p: string) => request<string>("GET", p, undefined, true),
  post: <T,>(p: string, b?: unknown) => request<T>("POST", p, b ?? {}),
  put: <T,>(p: string, b: unknown) => request<T>("PUT", p, b),
  del: <T,>(p: string) => request<T>("DELETE", p),
  upload: <T,>(p: string, f: FormData) => request<T>("POST", p, f),
};

/** Descarga un recurso protegido como archivo. */
export async function download(path: string, filename: string) {
  const res = await fetch(path, { headers: { Authorization: `Bearer ${await getToken()}` } });
  if (!res.ok) throw new ApiError(res.status, `Error ${res.status}`);
  const url = URL.createObjectURL(await res.blob());
  const a = document.createElement("a");
  a.href = url;
  a.download = filename;
  a.click();
  URL.revokeObjectURL(url);
}

// ------------------------------------------------------------------ tipos
export interface Me {
  username: string;
  full_name: string;
  email: string;
  roles: string[];
  is_admin: boolean;
  is_dba: boolean;
  is_dba_admin: boolean;
  can_execute: boolean;
  scopes: { connection_id: number; schema_name: string }[];
}

export interface DataType {
  name: string;
  length?: boolean;
  precision?: boolean;
  scale?: boolean;
  max_length?: number;
  default_length?: number;
  min_version?: string;
}

export interface Meta {
  engines: { key: string; label: string; data_types: DataType[] }[];
  operations: { type: string; label: string; destructive: boolean }[];
  object_privileges: string[];
  system_privileges: string[];
}

export interface Connection {
  id: number;
  name: string;
  engine: string;
  environment: string;
  description: string | null;
  host: string;
  port: number;
  service_name: string;
  catalog_user: string;
  exec_user: string | null;
  has_exec_account: boolean;
  allow_execution: boolean;
  required_approvals: number;
  active: boolean;
  server_version: string | null;
}

export interface Project {
  id: number;
  code: string;
  name: string;
  description: string | null;
  owner: string | null;
  active: boolean;
}

export interface SchemaInfo {
  name: string;
  account_status: string;
  created: string;
  default_tablespace: string;
  tables: number;
  objects: number;
  received_grants: number;
  kind: "OWNER" | "READER" | "EMPTY";
}

export interface DbObject {
  name: string;
  type: string;
  status: string;
  created: string;
  last_ddl_time: string;
  comments: string | null;
  target_schema: string | null;
  target_object: string | null;
}

export interface ColumnInfo {
  column_id: number | null;
  name: string;
  data_type: string;
  type_display: string;
  length: number | null;
  char_semantics: boolean;
  precision: number | null;
  scale: number | null;
  nullable: boolean;
  default_value: string | null;
  default_on_null: boolean;
  identity: boolean;
  identity_mode?: "ALWAYS" | "BY_DEFAULT" | "BY_DEFAULT_ON_NULL";
  identity_start?: number | null;
  identity_increment?: number | null;
  virtual_expression: string | null;
  invisible: boolean;
  comments: string | null;
}

export interface ConstraintInfo {
  name: string;
  type: "P" | "U" | "R" | "C";
  condition: string | null;
  status: string;
  enabled: boolean;
  validated: boolean;
  deferrable: boolean;
  initially_deferred: boolean;
  delete_rule: string | null;
  generated: string;
  index_name: string | null;
  ref_owner: string | null;
  ref_table: string | null;
  columns: string;
  ref_columns: string | null;
}

export interface IndexInfo {
  owner: string;
  name: string;
  type: string;
  kind: "NORMAL" | "UNIQUE" | "BITMAP" | "DOMAIN";
  uniqueness: string;
  status: string;
  visibility: string;
  compression: string | null;
  prefix_length: number | null;
  partitioned: string;
  locality: string | null;
  tablespace_name: string | null;
  indextype: string | null;
  parameters: string | null;
  constraint_name: string | null;
  columns: string;
  column_list: { expression: string; descending: boolean }[];
  function_based: boolean;
  reverse: boolean;
}

export interface TableInfo {
  schema: string;
  name: string;
  type: string;
  status: string;
  created: string;
  last_ddl_time: string;
  comments: string | null;
  tablespace_name: string | null;
  partitioned: string | null;
  temporary: string | null;
  duration: string | null;
  compression: string | null;
  compress_for: string | null;
  logging: string | null;
  read_only: string | null;
  columns: ColumnInfo[];
  constraints: ConstraintInfo[];
  referenced_by: { owner: string; table_name: string; name: string; columns: string }[];
  indexes: IndexInfo[];
  grants: { grantee: string; privileges: string[]; grantable: string[]; column_privileges: string[] }[];
  synonyms: { owner: string; name: string }[];
  partitioning: { partitioning_type: string; subpartitioning_type: string; interval: string | null; partition_count: number; columns: string } | null;
  partitions: { name: string; position: number; high_value: string | null; tablespace_name: string | null; interval: string }[];
  triggers: { owner: string; name: string; trigger_type: string; triggering_event: string; status: string }[];
  mv_log: { log_table: string; primary_key: string; rowids: string; sequence: string; include_new_values: string } | null;
}

export interface UserInfo {
  username: string;
  account_status: string;
  created: string;
  default_tablespace: string;
  profile: string;
  expiry_date: string | null;
  system_privileges: { privilege: string; admin_option: string }[];
  roles: { role: string; admin_option: string; default_role: string }[];
  quotas: { tablespace_name: string; bytes: number; max_bytes: number }[];
  object_privileges: { owner: string; object_name: string; object_type: string | null; privileges: string; grantable: string }[];
  synonyms: { name: string; target_schema: string; target_object: string }[];
}

export interface SearchHit {
  match_on: "OBJECT" | "COLUMN";
  schema: string;
  object_type: string;
  object_name: string;
  column_name: string | null;
  comments: string | null;
}

export type Operation = Record<string, unknown> & { type: string };

export interface Statement {
  sql: string;
  plsql: boolean;
  secret_placeholder: string | null;
}

export interface Finding {
  level: "error" | "warning" | "info";
  message: string;
  op_seq: number | null;
}

export interface Preview {
  operations: { seq: number; type: string; schema: string; object_name: string | null; statements: Statement[] }[];
  script: string;
  findings: Finding[];
}

export interface RequestSummary {
  id: number;
  code: string;
  title: string;
  status: string;
  connection: { id: number; name: string; environment: string };
  project: { id: number; code: string; name: string } | null;
  target_schemas: string[];
  operation_types: string[];
  requested_by: string;
  created_at: string;
  updated_at: string;
  submitted_at: string | null;
  decided_at: string | null;
  executed_at: string | null;
}

export interface RequestDetail extends RequestSummary {
  reason: string;
  purpose: string | null;
  ticket_ref: string | null;
  operations: {
    id: number;
    seq: number;
    type: string;
    schema: string;
    object_name: string | null;
    payload: Operation;
    statements: Statement[];
    status: string;
    message: string | null;
    executed_at: string | null;
    statements_done: number;
  }[];
  approvals: { approver: string; decision: string; comment: string | null; active: boolean; decided_at: string }[];
  comments: { id: number; author: string; body: string; created_at: string }[];
  executions: { id: number; executed_by: string; mode: string; status: string; log: string | null; notes: string | null; started_at: string; finished_at: string | null }[];
  required_approvals: number;
  permissions: { edit: boolean; submit: boolean; cancel: boolean; review: boolean; execute: boolean; auto_execute: boolean; self_execution: boolean; comment: boolean };
  eligible_reviewers: string[];
}

export interface HistoryEvent {
  at: string;
  username: string;
  action: string;
  details: Record<string, unknown> | null;
}

// ------------------------------------------------------------------ hooks
export const useMe = () => useQuery({ queryKey: ["me"], queryFn: () => api.get<Me>("/api/me"), staleTime: 60_000 });
export const useMeta = () => useQuery({ queryKey: ["meta"], queryFn: () => api.get<Meta>("/api/meta"), staleTime: Infinity });
export const useConnections = () =>
  useQuery({ queryKey: ["connections"], queryFn: () => api.get<Connection[]>("/api/connections"), staleTime: 60_000 });
export const useProjects = () =>
  useQuery({ queryKey: ["projects"], queryFn: () => api.get<Project[]>("/api/projects"), staleTime: 60_000 });

export const useSchemas = (connectionId?: number | null) =>
  useQuery({
    queryKey: ["schemas", connectionId],
    queryFn: () => api.get<SchemaInfo[]>(`/api/explorer/${connectionId}/schemas`),
    enabled: !!connectionId,
    staleTime: 60_000,
  });

export const useObjects = (connectionId?: number | null, schema?: string | null) =>
  useQuery({
    queryKey: ["objects", connectionId, schema],
    queryFn: () => api.get<DbObject[]>(`/api/explorer/${connectionId}/schemas/${schema}/objects`),
    enabled: !!connectionId && !!schema,
    staleTime: 60_000,
  });

export const useTable = (connectionId?: number | null, schema?: string | null, table?: string | null) =>
  useQuery({
    queryKey: ["table", connectionId, schema, table],
    queryFn: () => api.get<TableInfo>(`/api/explorer/${connectionId}/schemas/${schema}/tables/${table}`),
    enabled: !!connectionId && !!schema && !!table,
    staleTime: 30_000,
    retry: false,
  });

export const useUserInfo = (connectionId?: number | null, schema?: string | null) =>
  useQuery({
    queryKey: ["user", connectionId, schema],
    queryFn: () => api.get<UserInfo>(`/api/explorer/${connectionId}/schemas/${schema}/user`),
    enabled: !!connectionId && !!schema,
    staleTime: 30_000,
  });

export const fetchDdl = (connectionId: number, schema: string, type: string, name: string) =>
  api.get<{ ddl: string }>(
    `/api/explorer/${connectionId}/schemas/${schema}/ddl?type=${encodeURIComponent(type)}&name=${encodeURIComponent(name)}`,
  );

/** Mayor de la versión (p. ej. "19.21.0.0.0" -> 19). */
export const majorVersion = (v?: string | null) => (v ? Number(v.split(".")[0]) : null);

/** Meta con solo los tipos de dato que soporta la versión del servidor. */
export function metaForVersion(meta: Meta, version?: string | null): Meta {
  const major = majorVersion(version);
  if (!major) return meta;
  return {
    ...meta,
    engines: meta.engines.map((e) => ({ ...e, data_types: e.data_types.filter((t) => !t.min_version || major >= Number(t.min_version)) })),
  };
}
