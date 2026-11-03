// Formularios de operaciones, generados a partir de una definición por tipo. Cada formulario edita
// directamente el objeto de la operación (el mismo JSON que valida el servidor).
import { h, clear, field, select, check } from "../ui.js";

const f = (key, label, kind = "ident", extra = {}) => ({ key, label, kind, ...extra });
const YES_NO = [["", "(sin cambio)"], ["true", "Sí"], ["false", "No"]];
const COMPRESSION = [["", "(por defecto)"], ["NONE", "Sin compresión"], ["BASIC", "Básica"], ["ADVANCED", "Avanzada (licencia)"]];
const CODE_KINDS = ["PROCEDURE", "FUNCTION", "PACKAGE", "PACKAGE BODY", "TRIGGER", "VIEW", "MATERIALIZED VIEW", "TYPE"];

export const SPECS = {
  CREATE_TABLE: [f("schema", "Esquema", "ident", { req: 1 }), f("table", "Tabla", "ident", { req: 1 }), f("comment", "Comentario de la tabla", "text"),
    f("columns", "Columnas", "columns"), f("primary_key", "Llave primaria (columnas, separadas por coma)", "idents"),
    f("primary_key_name", "Nombre de la PK (opcional)"), f("constraints", "Restricciones (única, foránea, check)", "constraints"),
    f("tablespace", "Tablespace"), f("temporary", "Temporal", "sel", { options: [["", "No"], ["DELETE_ROWS", "Global temporal (ON COMMIT DELETE ROWS)"], ["PRESERVE_ROWS", "Global temporal (ON COMMIT PRESERVE ROWS)"]] }),
    f("compression", "Compresión", "sel", { options: COMPRESSION }), f("logging", "Logging", "tri"), f("partitioning", "Particionado", "partitioning")],
  ADD_COLUMN: [f("schema", "Esquema", "ident", { req: 1 }), f("table", "Tabla", "ident", { req: 1 }), f("column", "Columna nueva", "column")],
  MODIFY_COLUMN: [f("schema", "Esquema", "ident", { req: 1 }), f("table", "Tabla", "ident", { req: 1 }), f("column", "Definición nueva", "column"),
    f("previous", "Definición actual (del catálogo)", "previous")],
  DROP_COLUMN: [f("schema", "Esquema", "ident", { req: 1 }), f("table", "Tabla", "ident", { req: 1 }), f("column_name", "Columna", "ident", { req: 1 })],
  RENAME: [f("schema", "Esquema", "ident", { req: 1 }), f("target", "Qué se renombra", "sel", { options: [["TABLE", "Tabla"], ["COLUMN", "Columna"], ["CONSTRAINT", "Restricción"], ["INDEX", "Índice"]] }),
    f("table", "Tabla (para columna o restricción)"), f("old_name", "Nombre actual", "ident", { req: 1 }), f("new_name", "Nombre nuevo", "ident", { req: 1 })],
  SET_COMMENT: [f("schema", "Esquema", "ident", { req: 1 }), f("table", "Tabla o vista", "ident", { req: 1 }), f("column", "Columna (vacío = comentario de la tabla)"), f("comment", "Comentario", "textarea")],
  ALTER_TABLE_OPTIONS: [f("schema", "Esquema", "ident", { req: 1 }), f("table", "Tabla", "ident", { req: 1 }), f("logging", "Logging", "tri"),
    f("compression", "Compresión", "sel", { options: COMPRESSION }), f("read_only", "Solo lectura", "tri")],
  DROP_TABLE: [f("schema", "Esquema", "ident", { req: 1 }), f("table", "Tabla", "ident", { req: 1 }), f("cascade_constraints", "CASCADE CONSTRAINTS", "bool"), f("purge", "PURGE (sin papelera)", "bool")],
  ADD_CONSTRAINT: [f("schema", "Esquema", "ident", { req: 1 }), f("table", "Tabla", "ident", { req: 1 }), f("constraint", "Restricción", "constraint")],
  DROP_CONSTRAINT: [f("schema", "Esquema", "ident", { req: 1 }), f("table", "Tabla", "ident", { req: 1 }), f("constraint_name", "Restricción", "ident", { req: 1 }),
    f("cascade", "CASCADE", "bool"), f("index", "Índice de la PK/única", "sel", { options: [["", "(por defecto)"], ["KEEP", "Conservar"], ["DROP", "Eliminar"]] })],
  CONSTRAINT_STATE: [f("schema", "Esquema", "ident", { req: 1 }), f("table", "Tabla", "ident", { req: 1 }), f("constraint_name", "Restricción", "ident", { req: 1 }),
    f("action", "Acción", "sel", { options: [["ENABLE", "Habilitar"], ["DISABLE", "Deshabilitar"]] }), f("validate_existing", "Validar renglones existentes (al habilitar)", "bool")],
  CREATE_INDEX: [f("schema", "Esquema", "ident", { req: 1 }), f("table", "Tabla", "ident", { req: 1 }), f("index_name", "Nombre del índice", "ident", { req: 1 }),
    f("index_type", "Tipo", "sel", { options: [["NORMAL", "Normal (B-tree)"], ["UNIQUE", "Único"], ["BITMAP", "Bitmap"], ["DOMAIN", "De dominio (Oracle Text, espacial…)"]] }),
    f("columns", "Columnas o expresiones", "indexcols"), f("tablespace", "Tablespace"),
    f("compression", "Compresión", "sel", { options: [["", "Sin compresión"], ["PREFIX", "Por prefijo"], ["ADVANCED_LOW", "Avanzada LOW"], ["ADVANCED_HIGH", "Avanzada HIGH"]] }),
    f("prefix_length", "Longitud de prefijo", "num"), f("reverse", "REVERSE", "bool"), f("local", "LOCAL (tabla particionada)", "bool"),
    f("invisible", "Invisible", "bool"), f("online", "ONLINE", "bool"), f("indextype", "INDEXTYPE (dominio, p. ej. CTXSYS.CONTEXT)", "text"),
    f("parameters", "PARAMETERS (dominio)", "text")],
  ALTER_INDEX: [f("schema", "Esquema", "ident", { req: 1 }), f("index_name", "Índice", "ident", { req: 1 }),
    f("action", "Acción", "sel", { options: [["REBUILD", "Reconstruir"], ["VISIBLE", "Hacer visible"], ["INVISIBLE", "Hacer invisible"]] }),
    f("tablespace", "Tablespace (al reconstruir)"), f("online", "ONLINE", "bool")],
  DROP_INDEX: [f("schema", "Esquema", "ident", { req: 1 }), f("index_name", "Índice", "ident", { req: 1 }), f("online", "ONLINE", "bool")],
  ADD_PARTITION: [f("schema", "Esquema", "ident", { req: 1 }), f("table", "Tabla", "ident", { req: 1 }),
    f("method", "Particionado de la tabla", "sel", { options: ["RANGE", "LIST", "HASH"] }), f("partition", "Partición", "partition")],
  DROP_PARTITION: [f("schema", "Esquema", "ident", { req: 1 }), f("table", "Tabla", "ident", { req: 1 }), f("partition_name", "Partición", "ident", { req: 1 }),
    f("update_indexes", "UPDATE INDEXES", "bool")],
  CREATE_SEQUENCE: [f("schema", "Esquema", "ident", { req: 1 }), f("name", "Secuencia", "ident", { req: 1 }), f("start_with", "Inicia en", "num"),
    f("increment_by", "Incremento", "num"), f("min_value", "Mínimo", "num"), f("max_value", "Máximo", "num"), f("cache", "Cache (0 = NOCACHE)", "num"),
    f("cycle", "CYCLE", "bool"), f("order", "ORDER", "bool")],
  ALTER_SEQUENCE: [f("schema", "Esquema", "ident", { req: 1 }), f("name", "Secuencia", "ident", { req: 1 }), f("restart_with", "Reiniciar en (18c+)", "num"),
    f("increment_by", "Incremento", "num"), f("min_value", "Mínimo", "num"), f("max_value", "Máximo", "num"), f("cache", "Cache (0 = NOCACHE)", "num"),
    f("cycle", "CYCLE", "tri"), f("order", "ORDER", "tri")],
  CREATE_MV_LOG: [f("schema", "Esquema", "ident", { req: 1 }), f("table", "Tabla", "ident", { req: 1 }), f("with_primary_key", "WITH PRIMARY KEY", "bool"),
    f("with_rowid", "WITH ROWID", "bool"), f("with_sequence", "WITH SEQUENCE", "bool"), f("columns", "Columnas (separadas por coma)", "idents"),
    f("including_new_values", "INCLUDING NEW VALUES", "bool")],
  DROP_MV_LOG: [f("schema", "Esquema", "ident", { req: 1 }), f("table", "Tabla", "ident", { req: 1 })],
  GRANT: [f("schema", "Esquema del objeto", "ident", { req: 1 }), f("object", "Objeto", "ident", { req: 1 }), f("grantee", "Usuario o rol que recibe", "ident", { req: 1 }),
    f("privileges", "Privilegios", "privs"), f("columns", "Solo estas columnas (INSERT/UPDATE/REFERENCES)", "idents"), f("with_grant_option", "WITH GRANT OPTION", "bool")],
  REVOKE: [f("schema", "Esquema del objeto", "ident", { req: 1 }), f("object", "Objeto", "ident", { req: 1 }), f("grantee", "Usuario o rol", "ident", { req: 1 }),
    f("privileges", "Privilegios", "privs")],
  CREATE_SYNONYM: [f("schema", "Dueño del sinónimo (o PUBLIC)", "ident", { req: 1 }), f("synonym", "Sinónimo", "ident", { req: 1 }),
    f("target_schema", "Esquema destino", "ident", { req: 1 }), f("target_object", "Objeto destino", "ident", { req: 1 }), f("replace", "OR REPLACE", "bool")],
  DROP_SYNONYM: [f("schema", "Dueño del sinónimo (o PUBLIC)", "ident", { req: 1 }), f("synonym", "Sinónimo", "ident", { req: 1 })],
  CREATE_USER: [f("schema", "Usuario a crear", "ident", { req: 1 }), f("kind", "Tipo", "sel", { options: [["READER", "Lector (BI, consultas)"], ["OWNER", "Dueño de objetos"]] }),
    f("default_tablespace", "Tablespace por defecto"), f("quota", "Cuota (p. ej. 100M o UNLIMITED)", "text"),
    f("system_privileges", "Privilegios de sistema", "sysprivs"), f("roles", "Roles (separados por coma)", "idents")],
  CREATE_OR_REPLACE_CODE: [f("schema", "Esquema", "ident", { req: 1 }), f("kind", "Tipo", "sel", { options: CODE_KINDS }), f("name", "Nombre", "ident", { req: 1 }),
    f("source", "Código completo (desde CREATE …)", "code")],
  DROP_CODE: [f("schema", "Esquema", "ident", { req: 1 }), f("kind", "Tipo", "sel", { options: [...CODE_KINDS, "SEQUENCE"] }), f("name", "Nombre", "ident", { req: 1 })],
  CUSTOM_SQL: [f("schema", "Esquema principal afectado", "ident", { req: 1 }), f("description", "Qué hace este SQL", "text"), f("sql", "SQL", "code")],
};

/** Operación vacía de un tipo. */
export function blankOp(type) {
  const base = { type, schema: "" };
  const d = {
    CREATE_TABLE: { table: "", comment: null, columns: [blankColumn()], primary_key: [], primary_key_name: null, constraints: [], tablespace: null, temporary: null, compression: null, logging: null, partitioning: null },
    ADD_COLUMN: { table: "", column: blankColumn() },
    MODIFY_COLUMN: { table: "", column: blankColumn(), previous: null },
    DROP_COLUMN: { table: "", column_name: "" },
    RENAME: { target: "TABLE", table: null, old_name: "", new_name: "" },
    SET_COMMENT: { table: "", column: null, comment: "" },
    ALTER_TABLE_OPTIONS: { table: "", logging: null, compression: null, read_only: null },
    DROP_TABLE: { table: "", cascade_constraints: false, purge: false },
    ADD_CONSTRAINT: { table: "", constraint: blankConstraint("UNIQUE") },
    DROP_CONSTRAINT: { table: "", constraint_name: "", cascade: false, index: null },
    CONSTRAINT_STATE: { table: "", constraint_name: "", action: "DISABLE", validate_existing: true },
    CREATE_INDEX: { table: "", index_name: "", index_type: "NORMAL", columns: [{ expression: "", descending: false }] },
    ALTER_INDEX: { index_name: "", action: "REBUILD", online: false, tablespace: null },
    DROP_INDEX: { index_name: "", online: false },
    ADD_PARTITION: { table: "", method: "RANGE", partition: { name: "", values: "", tablespace: null } },
    DROP_PARTITION: { table: "", partition_name: "", update_indexes: true },
    CREATE_SEQUENCE: { name: "", cycle: false, order: false },
    ALTER_SEQUENCE: { name: "" },
    CREATE_MV_LOG: { table: "", with_primary_key: true, with_rowid: false, with_sequence: false, columns: [], including_new_values: false },
    DROP_MV_LOG: { table: "" },
    GRANT: { object: "", grantee: "", privileges: ["SELECT"], columns: [], with_grant_option: false },
    REVOKE: { object: "", grantee: "", privileges: ["SELECT"], columns: [] },
    CREATE_SYNONYM: { synonym: "", target_schema: "", target_object: "", replace: true },
    DROP_SYNONYM: { synonym: "" },
    CREATE_USER: { kind: "READER", default_tablespace: null, quota: null, system_privileges: ["CREATE SESSION"], roles: [] },
    CREATE_OR_REPLACE_CODE: { kind: "PROCEDURE", name: "", source: "" },
    DROP_CODE: { kind: "PROCEDURE", name: "" },
    CUSTOM_SQL: { description: "", sql: "" },
  };
  return { ...base, ...structuredClone(d[type] || {}) };
}

export function blankColumn() {
  return { name: "", data_type: "VARCHAR2", length: 100, char_semantics: false, precision: null, scale: null, nullable: true, default: null,
    default_on_null: false, comment: null, identity: false, identity_mode: "BY_DEFAULT_ON_NULL", identity_start: null, identity_increment: null,
    virtual_expression: null, invisible: false };
}

function blankConstraint(kind) {
  return { kind, name: null, columns: [], ref_schema: null, ref_table: null, ref_columns: [], on_delete: "NO_ACTION", condition: null,
    deferrable: false, initially_deferred: false, enabled: true, validated: true, index_tablespace: null };
}

// ------------------------------------------------------------------ controles
const emptyNull = v => (v === "" ? null : v);

function textInput(obj, key, { upper = false, placeholder = "" } = {}) {
  const i = h("input", { value: obj[key] ?? "", placeholder });
  i.addEventListener("input", () => { obj[key] = emptyNull(upper ? i.value.trim() : i.value); });
  if (upper) i.addEventListener("blur", () => { if (i.value) { i.value = i.value.trim().toUpperCase(); obj[key] = i.value; } });
  return i;
}

function numInput(obj, key) {
  const i = h("input", { type: "number", value: obj[key] ?? "" });
  i.addEventListener("input", () => { obj[key] = i.value === "" ? null : Number(i.value); });
  return i;
}

function listInput(obj, key) {
  const i = h("input", { value: (obj[key] || []).join(", "), placeholder: "A, B, C" });
  i.addEventListener("input", () => { obj[key] = i.value.split(",").map(s => s.trim().toUpperCase()).filter(Boolean); });
  return i;
}

function triSelect(obj, key) {
  return select(YES_NO, obj[key] === true ? "true" : obj[key] === false ? "false" : "", v => { obj[key] = v === "" ? null : v === "true"; });
}

function multiCheck(obj, key, options) {
  obj[key] ||= [];
  return h("div", { class: "row" }, options.map(o => check(o, obj[key].includes(o), on => {
    obj[key] = on ? [...new Set([...obj[key], o])] : obj[key].filter(x => x !== o);
  })));
}

// ------------------------------------------------------------------ columnas
function typeInfo(meta, t) {
  return meta.engines[0].data_types.find(x => x.name === t) || {};
}

export function columnEditor(col, meta, onRemove) {
  const more = h("div", { class: "grid3 hidden", style: { marginTop: "6px" } });
  const sizeBox = h("div", { class: "row" });
  const renderSize = () => {
    const ti = typeInfo(meta, col.data_type);
    clear(sizeBox, 
      ti.length ? field("Longitud", numInput(col, "length")) : null,
      ti.precision ? field(col.data_type?.startsWith("TIMESTAMP") ? "Fracción seg." : "Precisión", numInput(col, "precision")) : null,
      ti.scale ? field("Escala", numInput(col, "scale")) : null,
      ti.length && ["VARCHAR2", "CHAR"].includes(col.data_type) ? check("CHAR", col.char_semantics, v => col.char_semantics = v) : null);
  };
  const types = [["", "(virtual: deduce)"], ...meta.engines[0].data_types.map(t => [t.name, t.name + (t.min_version ? ` (${t.min_version}c+)` : "")])];
  const typeSel = select(types, col.data_type ?? "", v => {
    col.data_type = v || null;
    const ti = typeInfo(meta, v);
    if (ti.length && !col.length) col.length = ti.default_length;
    if (!ti.length) col.length = null;
    if (!ti.precision) col.precision = null;
    if (!ti.scale) col.scale = null;
    renderSize();
  });
  renderSize();
  more.append(
    field("DEFAULT (expresión)", textInput(col, "default", { placeholder: "p. ej. SYSDATE o 'A'" })),
    h("div", { class: "stack" }, check("DEFAULT ON NULL", col.default_on_null, v => col.default_on_null = v),
      check("Invisible", col.invisible, v => col.invisible = v)),
    field("Comentario", textInput(col, "comment")),
    field("Virtual: GENERATED ALWAYS AS (…)", textInput(col, "virtual_expression")),
    h("div", { class: "stack" }, check("Identidad", col.identity, v => col.identity = v),
      select([["BY_DEFAULT_ON_NULL", "BY DEFAULT ON NULL"], ["BY_DEFAULT", "BY DEFAULT"], ["ALWAYS", "ALWAYS"]], col.identity_mode, v => col.identity_mode = v)),
    field("Identidad: inicia / incrementa", h("div", { class: "row" }, numInput(col, "identity_start"), numInput(col, "identity_increment"))));
  return h("div", { class: "sub" },
    h("div", { class: "row top" },
      h("div", { style: { flex: "1.2", minWidth: "150px" } }, field("Columna", textInput(col, "name", { upper: true }))),
      h("div", { style: { flex: "1.2", minWidth: "150px" } }, field("Tipo", typeSel)),
      sizeBox,
      check("Acepta nulos", col.nullable, v => col.nullable = v),
      h("button", { class: "link", onclick: () => more.classList.toggle("hidden") }, "Más opciones"),
      onRemove ? h("button", { class: "link", onclick: onRemove, title: "Quitar" }, "✕") : null),
    more);
}

function columnsEditor(op, key, meta) {
  const box = h("div");
  const render = () => clear(box, ...op[key].map((c, i) => columnEditor(c, meta, op[key].length > 1 ? () => { op[key].splice(i, 1); render(); } : null)),
    h("button", { class: "small", onclick: () => { op[key].push(blankColumn()); render(); } }, "+ Columna"));
  render();
  return box;
}

// ------------------------------------------------------------------ restricciones
function constraintEditor(c, onRemove) {
  const body = h("div", { class: "grid3" });
  const render = () => {
    clear(body, 
      field("Tipo", select([["PRIMARY_KEY", "Llave primaria"], ["UNIQUE", "Única"], ["FOREIGN_KEY", "Foránea"], ["CHECK", "Check"]], c.kind, v => { c.kind = v; render(); })),
      field("Nombre (opcional)", textInput(c, "name", { upper: true })),
      c.kind !== "CHECK" ? field("Columnas", listInput(c, "columns")) : field("Condición CHECK", textInput(c, "condition", { placeholder: "p. ej. MONTO > 0" })),
      c.kind === "FOREIGN_KEY" ? [field("Esquema referenciado (vacío = el mismo)", textInput(c, "ref_schema", { upper: true })),
        field("Tabla referenciada", textInput(c, "ref_table", { upper: true })), field("Columnas referenciadas (vacío = su PK)", listInput(c, "ref_columns")),
        field("ON DELETE", select([["NO_ACTION", "Sin acción"], ["CASCADE", "CASCADE"], ["SET_NULL", "SET NULL"]], c.on_delete, v => c.on_delete = v))] : null,
      ["PRIMARY_KEY", "UNIQUE"].includes(c.kind) ? field("Tablespace del índice", textInput(c, "index_tablespace", { upper: true })) : null,
      h("div", { class: "stack" }, check("DEFERRABLE", c.deferrable, v => c.deferrable = v), check("INITIALLY DEFERRED", c.initially_deferred, v => c.initially_deferred = v)),
      h("div", { class: "stack" }, check("Habilitada", c.enabled, v => c.enabled = v), check("Validar existentes", c.validated, v => c.validated = v)));
  };
  render();
  return h("div", { class: "sub" }, onRemove ? h("div", { class: "row" }, h("span", { class: "right" }), h("button", { class: "link", onclick: onRemove }, "✕ Quitar")) : null, body);
}

function constraintsEditor(op, key) {
  const box = h("div");
  const render = () => clear(box, ...op[key].map((c, i) => constraintEditor(c, () => { op[key].splice(i, 1); render(); })),
    h("button", { class: "small", onclick: () => { op[key].push(blankConstraint("UNIQUE")); render(); } }, "+ Restricción"));
  render();
  return box;
}

// ------------------------------------------------------------------ particiones e índices
function partitionEditor(p, method, onRemove) {
  return h("div", { class: "row" },
    h("div", { style: { flex: 1 } }, field("Partición", textInput(p, "name", { upper: true }))),
    method !== "HASH" ? h("div", { style: { flex: 2 } }, field(method === "RANGE" ? "VALUES LESS THAN (…)" : "VALUES (…)", textInput(p, "values", { placeholder: method === "RANGE" ? "DATE '2027-01-01' o MAXVALUE" : "'A', 'B'" }))) : null,
    h("div", { style: { flex: 1 } }, field("Tablespace", textInput(p, "tablespace", { upper: true }))),
    onRemove ? h("button", { class: "link", onclick: onRemove }, "✕") : null);
}

function partitioningEditor(op, key) {
  const box = h("div");
  const render = () => {
    const pt = op[key];
    if (!pt) {
      clear(box, h("button", { class: "small", onclick: () => { op[key] = { method: "RANGE", columns: [], interval: null, partitions: [{ name: "", values: "", tablespace: null }], hash_count: null }; render(); } }, "Particionar la tabla"));
      return;
    }
    const parts = h("div");
    const renderParts = () => clear(parts, ...pt.partitions.map((p, i) => partitionEditor(p, pt.method, () => { pt.partitions.splice(i, 1); renderParts(); })),
      h("button", { class: "small", onclick: () => { pt.partitions.push({ name: "", values: pt.method === "HASH" ? null : "", tablespace: null }); renderParts(); } }, "+ Partición"));
    renderParts();
    clear(box, h("div", { class: "sub" }, h("div", { class: "grid3" },
      field("Método", select(["RANGE", "LIST", "HASH"], pt.method, v => { pt.method = v; render(); })),
      field("Columnas de partición", listInput(pt, "columns")),
      pt.method === "RANGE" ? field("INTERVAL (opcional)", textInput(pt, "interval", { placeholder: "NUMTOYMINTERVAL(1,'MONTH')" })) : null,
      pt.method === "HASH" ? field("Número de particiones (si no las nombras)", numInput(pt, "hash_count")) : null),
      parts, h("button", { class: "link", onclick: () => { op[key] = null; render(); } }, "Quitar particionado")));
  };
  render();
  return box;
}

function indexColumnsEditor(op, key) {
  const box = h("div");
  const render = () => clear(box, ...op[key].map((c, i) => h("div", { class: "row", style: { marginBottom: "6px" } },
    h("div", { style: { flex: 1 } }, textInput(c, "expression", { placeholder: "COLUMNA o expresión, p. ej. UPPER(NOMBRE)" })),
    check("DESC", c.descending, v => c.descending = v),
    op[key].length > 1 ? h("button", { class: "link", onclick: () => { op[key].splice(i, 1); render(); } }, "✕") : null)),
    h("button", { class: "small", onclick: () => { op[key].push({ expression: "", descending: false }); render(); } }, "+ Columna"));
  render();
  return box;
}

// ------------------------------------------------------------------ formulario completo
export function opForm(op, meta, ctx = {}) {
  const spec = SPECS[op.type] || [];
  const simple = h("div", { class: "grid2" });
  const blocks = h("div");
  for (const s of spec) {
    let ctrl = null, block = null;
    switch (s.kind) {
      case "ident": ctrl = textInput(op, s.key, { upper: true }); break;
      case "text": ctrl = textInput(op, s.key); break;
      case "textarea": ctrl = h("textarea", { value: op[s.key] ?? "", oninput: e => op[s.key] = e.target.value }); break;
      case "code": block = field(s.label, h("textarea", { class: "code", spellcheck: "false", value: op[s.key] ?? "", oninput: e => op[s.key] = e.target.value }), { required: true }); break;
      case "num": ctrl = numInput(op, s.key); break;
      case "bool": ctrl = check(s.label, op[s.key], v => op[s.key] = v); break;
      case "tri": ctrl = triSelect(op, s.key); break;
      case "sel": ctrl = select(s.options, op[s.key] ?? "", v => { op[s.key] = emptyNull(v); }); break;
      case "idents": ctrl = listInput(op, s.key); break;
      case "privs": block = field(s.label, multiCheck(op, s.key, meta.object_privileges)); break;
      case "sysprivs": block = field(s.label, multiCheck(op, s.key, meta.system_privileges)); break;
      case "columns": block = h("div", {}, h("h3", {}, s.label), columnsEditor(op, s.key, meta)); break;
      case "column": op[s.key] ||= blankColumn(); block = h("div", {}, h("h3", {}, s.label), columnEditor(op[s.key], meta)); break;
      case "previous": block = op.previous ? h("details", {}, h("summary", { class: "small muted" }, s.label),
        h("pre", {}, JSON.stringify(op.previous, null, 1))) : (ctx.loadPrevious ? h("div", { class: "row" },
        h("span", { class: "small muted" }, "Sin la definición actual se genera el cambio completo."),
        h("button", { class: "small", onclick: () => ctx.loadPrevious(op) }, "Cargar definición actual")) : null); break;
      case "constraints": block = h("div", {}, h("h3", {}, s.label), constraintsEditor(op, s.key)); break;
      case "constraint": op[s.key] ||= blankConstraint("UNIQUE"); block = h("div", {}, h("h3", {}, s.label), constraintEditor(op[s.key])); break;
      case "partitioning": block = h("div", {}, h("h3", {}, s.label), partitioningEditor(op, s.key)); break;
      case "partition": op[s.key] ||= { name: "", values: "", tablespace: null }; block = h("div", {}, h("h3", {}, s.label), partitionEditor(op[s.key], op.method)); break;
      case "indexcols": block = h("div", {}, h("h3", {}, s.label), indexColumnsEditor(op, s.key)); break;
    }
    if (ctrl) simple.append(s.kind === "bool" ? h("div", { style: { alignSelf: "end" } }, ctrl) : field(s.label, ctrl, { required: !!s.req }));
    if (block) blocks.append(block);
  }
  return h("div", {}, simple, blocks);
}
