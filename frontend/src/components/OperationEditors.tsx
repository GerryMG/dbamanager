import {
  ActionIcon, Autocomplete, Badge, Button, Card, Checkbox, Divider, Group, MultiSelect, NumberInput, ScrollArea,
  SegmentedControl, Select, SimpleGrid, Stack, Switch, Table, TagsInput, Text, Textarea, TextInput, Tooltip,
} from "@mantine/core";
import { IconChevronDown, IconChevronRight, IconDownload, IconPlus, IconTrash } from "@tabler/icons-react";
import { Fragment, useState } from "react";
import { DataType, fetchDdl, Meta, Operation, useObjects, useSchemas, useTable } from "../api";
import { notifyError } from "../ui";

// ------------------------------------------------------------------ tipos locales
export type Col = {
  name: string;
  data_type: string | null;
  length?: number | null;
  char_semantics?: boolean;
  precision?: number | null;
  scale?: number | null;
  nullable: boolean;
  default?: string | null;
  default_on_null?: boolean;
  comment?: string | null;
  identity?: boolean;
  identity_mode?: "ALWAYS" | "BY_DEFAULT" | "BY_DEFAULT_ON_NULL";
  identity_start?: number | null;
  identity_increment?: number | null;
  virtual_expression?: string | null;
  invisible?: boolean;
};

export type ConstraintDef = {
  kind: "PRIMARY_KEY" | "UNIQUE" | "FOREIGN_KEY" | "CHECK";
  name?: string | null;
  columns?: string[];
  ref_schema?: string | null;
  ref_table?: string | null;
  ref_columns?: string[];
  on_delete?: "NO_ACTION" | "CASCADE" | "SET_NULL";
  condition?: string | null;
  deferrable?: boolean;
  initially_deferred?: boolean;
  enabled?: boolean;
  validated?: boolean;
  index_tablespace?: string | null;
};

type PartitionDef = { name: string; values?: string | null; tablespace?: string | null };
type Partitioning = { method: "RANGE" | "LIST" | "HASH"; columns: string[]; interval?: string | null; partitions: PartitionDef[]; hash_count?: number | null };

interface EditorProps {
  op: Operation;
  onChange: (op: Operation) => void;
  connectionId: number | null;
  meta: Meta;
}

const up = (v: string) => v.toUpperCase().replace(/\s/g, "");
const s = (v: unknown) => (v ?? "") as string;
const num = (v: string | number) => (v === "" || v === undefined ? null : Number(v));
const NUMERIC = ["NUMBER", "INTEGER"];

// ------------------------------------------------------------------ selectores con sugerencias
function SchemaInput({ connectionId, label, value, onChange, extra = [], description, required = true }: { connectionId: number | null; label: string; value: string; onChange: (v: string) => void; extra?: string[]; description?: string; required?: boolean }) {
  const { data } = useSchemas(connectionId);
  const options = [...extra, ...(data ?? []).map((x) => x.name)];
  return <Autocomplete label={label} description={description} data={[...new Set(options)]} value={value} onChange={(v) => onChange(up(v))} required={required} />;
}

function ObjectInput({ connectionId, schema, label, value, onChange, types, description }: { connectionId: number | null; schema: string; label: string; value: string; onChange: (v: string) => void; types?: string[]; description?: string }) {
  const { data } = useObjects(connectionId, schema || null);
  const options = (data ?? []).filter((o) => !types || types.includes(o.type)).map((o) => o.name);
  return <Autocomplete label={label} description={description} data={[...new Set(options)]} value={value} onChange={(v) => onChange(up(v))} required />;
}

/** Lista de columnas: sugiere las de la tabla, pero admite escribir (p. ej. tablas que se crean en la solicitud). */
function ColumnsInput({ connectionId, schema, table, label, value, onChange, extra = [], description }: { connectionId: number | null; schema: string; table: string; label: string; value: string[]; onChange: (v: string[]) => void; extra?: string[]; description?: string }) {
  const { data } = useTable(connectionId, schema || null, table || null);
  const options = [...new Set([...extra, ...(data?.columns ?? []).map((c) => c.name)])];
  return <TagsInput label={label} description={description} data={options} value={value} onChange={(v) => onChange(v.map(up).filter(Boolean))} clearable acceptValueOnBlur />;
}

function TableBase({ op, set, connectionId, tableTypes = ["TABLE"] }: { op: Operation; set: (p: Record<string, unknown>) => void; connectionId: number | null; tableTypes?: string[] }) {
  return (
    <>
      <SchemaInput connectionId={connectionId} label="Esquema" value={s(op.schema)} onChange={(v) => set({ schema: v })} />
      <ObjectInput connectionId={connectionId} schema={s(op.schema)} label="Tabla" types={tableTypes} value={s(op.table)} onChange={(v) => set({ table: v })} />
    </>
  );
}

// ------------------------------------------------------------------ columnas
function typeSpec(meta: Meta, name: string | null): DataType | undefined {
  return meta.engines[0].data_types.find((t) => t.name === name);
}

type ColumnFieldsProps = { col: Col; onChange: (c: Col) => void; meta: Meta; compact?: boolean };

const ColumnFields = (props: ColumnFieldsProps) => <>{columnFieldNodes(props)}</>;

/** Campos principales de una columna como lista (para celdas de tabla o una rejilla). */
function columnFieldNodes({ col, onChange, meta, compact = false }: ColumnFieldsProps): React.ReactNode[] {
  const spec = typeSpec(meta, col.data_type);
  const types = meta.engines[0].data_types.map((t) => t.name);
  const set = (patch: Partial<Col>) => onChange({ ...col, ...patch });
  const size = compact ? "xs" : "sm";
  const virtual = !!col.virtual_expression;
  return [
    <TextInput key="f0" size={size} label={compact ? undefined : "Nombre"} value={col.name} onChange={(e) => set({ name: up(e.currentTarget.value) })} placeholder="COLUMNA" />,
    <Select key="f1" size={size} label={compact ? undefined : "Tipo"} data={types} value={col.data_type} searchable clearable={virtual}
      placeholder={virtual ? "(automático)" : undefined}
      onChange={(v) => {
        const sp = typeSpec(meta, v);
        set({ data_type: v, length: sp?.length ? col.length ?? sp.default_length : null, precision: sp?.precision ? col.precision : null, scale: sp?.scale ? col.scale : null, identity: v && NUMERIC.includes(v) ? col.identity : false, char_semantics: v === "VARCHAR2" || v === "CHAR" ? col.char_semantics : false });
      }} />,
    <NumberInput key="f2" size={size} label={compact ? undefined : spec?.length ? "Longitud" : "Precisión"} placeholder={spec?.length ? "long." : spec?.precision ? "prec." : "—"}
      disabled={!spec?.length && !spec?.precision} min={0} max={spec?.max_length ?? 126}
      value={(spec?.length ? col.length : col.precision) ?? ""}
      onChange={(v) => (spec?.length ? set({ length: num(v) }) : set({ precision: num(v) }))} />,
    <NumberInput key="f3" size={size} label={compact ? undefined : "Escala"} placeholder={spec?.scale ? "escala" : "—"} disabled={!spec?.scale} value={col.scale ?? ""} onChange={(v) => set({ scale: num(v) })} />,
    <Checkbox key="f4" size={size} label={compact ? undefined : "Permite nulos"} checked={col.nullable} onChange={(e) => set({ nullable: e.currentTarget.checked })} mt={compact ? 0 : 28} />,
    <TextInput key="f5" size={size} label={compact ? undefined : "Default"} value={col.default ?? ""} onChange={(e) => set({ default: e.currentTarget.value || null, default_on_null: e.currentTarget.value ? col.default_on_null : false })}
      placeholder={col.identity ? "identidad" : virtual ? "virtual" : "p. ej. SYSDATE, 0, 'S'"} disabled={col.identity || virtual} />,
    <TextInput key="f6" size={size} label={compact ? undefined : "Comentario"} value={col.comment ?? ""} onChange={(e) => set({ comment: e.currentTarget.value || null })} />,
  ];
}

/** Opciones avanzadas: identidad, DEFAULT ON NULL, columna virtual, invisible, semántica CHAR. */
function ColumnAdvanced({ col, onChange }: { col: Col; onChange: (c: Col) => void }) {
  const set = (patch: Partial<Col>) => onChange({ ...col, ...patch });
  const numeric = !!col.data_type && NUMERIC.includes(col.data_type);
  return (
    <SimpleGrid cols={{ base: 1, sm: 2, lg: 4 }} spacing="xs">
      <Stack gap={4}>
        <Switch size="xs" label="Identidad (autonumérica)" disabled={!numeric || !!col.virtual_expression} checked={!!col.identity}
          onChange={(e) => set({ identity: e.currentTarget.checked, default: null, default_on_null: false, nullable: e.currentTarget.checked ? false : col.nullable })} />
        {col.identity && (
          <>
            <Select size="xs" data={[{ value: "BY_DEFAULT_ON_NULL", label: "BY DEFAULT ON NULL" }, { value: "BY_DEFAULT", label: "BY DEFAULT" }, { value: "ALWAYS", label: "ALWAYS (no se puede insertar valor)" }]}
              value={col.identity_mode ?? "BY_DEFAULT_ON_NULL"} onChange={(v) => set({ identity_mode: v as Col["identity_mode"] })} allowDeselect={false} />
            <Group gap={4} grow>
              <NumberInput size="xs" placeholder="inicia en" value={col.identity_start ?? ""} onChange={(v) => set({ identity_start: num(v) })} />
              <NumberInput size="xs" placeholder="incremento" value={col.identity_increment ?? ""} onChange={(v) => set({ identity_increment: num(v) })} />
            </Group>
          </>
        )}
      </Stack>
      <TextInput size="xs" label="Columna virtual: expresión" placeholder="p. ej. SUBTOTAL * 0.16" description="GENERATED ALWAYS AS (…) VIRTUAL"
        value={col.virtual_expression ?? ""} disabled={!!col.identity}
        onChange={(e) => set({ virtual_expression: e.currentTarget.value || null, default: e.currentTarget.value ? null : col.default })} />
      <Stack gap={4}>
        <Switch size="xs" label="DEFAULT ON NULL (también cuando insertan NULL)" disabled={!col.default} checked={!!col.default_on_null} onChange={(e) => set({ default_on_null: e.currentTarget.checked })} />
        <Switch size="xs" label="Invisible (no sale en SELECT *)" checked={!!col.invisible} onChange={(e) => set({ invisible: e.currentTarget.checked })} />
      </Stack>
      <Switch size="xs" label="Longitud en caracteres (CHAR) en vez de bytes" disabled={col.data_type !== "VARCHAR2" && col.data_type !== "CHAR"}
        checked={!!col.char_semantics} onChange={(e) => set({ char_semantics: e.currentTarget.checked })} />
    </SimpleGrid>
  );
}

const colBadges = (c: Col) => [
  c.identity && "identidad", c.virtual_expression && "virtual", c.invisible && "invisible", c.default_on_null && "on null", c.char_semantics && "CHAR",
].filter(Boolean) as string[];

// ------------------------------------------------------------------ restricciones
const KIND_LABEL: Record<ConstraintDef["kind"], string> = { PRIMARY_KEY: "Llave primaria", UNIQUE: "Única", FOREIGN_KEY: "Llave foránea", CHECK: "Check" };

function ConstraintEditor({ c, onChange, connectionId, schema, table, columnOptions = [], allowPk = true }: { c: ConstraintDef; onChange: (c: ConstraintDef) => void; connectionId: number | null; schema: string; table: string; columnOptions?: string[]; allowPk?: boolean }) {
  const set = (patch: Partial<ConstraintDef>) => onChange({ ...c, ...patch });
  const refSchema = c.ref_schema || schema;
  const kinds = (Object.keys(KIND_LABEL) as ConstraintDef["kind"][]).filter((k) => allowPk || k !== "PRIMARY_KEY");
  return (
    <Stack gap="xs">
      <Group align="flex-end">
        <SegmentedControl size="xs" data={kinds.map((k) => ({ value: k, label: KIND_LABEL[k] }))} value={c.kind} onChange={(v) => set({ kind: v as ConstraintDef["kind"] })} />
        <TextInput size="xs" label="Nombre" placeholder="(opcional, recomendado)" value={c.name ?? ""} onChange={(e) => set({ name: up(e.currentTarget.value) || null })} w={220} />
      </Group>
      {c.kind !== "CHECK" && (
        <ColumnsInput connectionId={connectionId} schema={schema} table={table} label="Columnas" extra={columnOptions} value={c.columns ?? []} onChange={(v) => set({ columns: v })} />
      )}
      {c.kind === "FOREIGN_KEY" && (
        <SimpleGrid cols={{ base: 1, sm: 4 }} spacing="xs">
          <SchemaInput connectionId={connectionId} label="Esquema referenciado" required={false} description="Vacío = mismo esquema" value={s(c.ref_schema)} onChange={(v) => set({ ref_schema: v || null })} />
          <ObjectInput connectionId={connectionId} schema={refSchema} label="Tabla referenciada" types={["TABLE"]} value={s(c.ref_table)} onChange={(v) => set({ ref_table: v })} />
          <ColumnsInput connectionId={connectionId} schema={refSchema} table={s(c.ref_table)} label="Columnas referenciadas" description="Vacío = su llave primaria" value={c.ref_columns ?? []} onChange={(v) => set({ ref_columns: v })} />
          <Select label="Al borrar el padre" data={[{ value: "NO_ACTION", label: "Impedir (NO ACTION)" }, { value: "CASCADE", label: "Borrar hijos (CASCADE)" }, { value: "SET_NULL", label: "Poner NULL (SET NULL)" }]}
            value={c.on_delete ?? "NO_ACTION"} onChange={(v) => set({ on_delete: v as ConstraintDef["on_delete"] })} allowDeselect={false} />
        </SimpleGrid>
      )}
      {c.kind === "CHECK" && (
        <TextInput label="Condición" placeholder="p. ej. ESTATUS IN ('A','C') o MONTO >= 0" value={c.condition ?? ""} onChange={(e) => set({ condition: e.currentTarget.value })} styles={{ input: { fontFamily: "monospace" } }} />
      )}
      <Group gap="md">
        <Switch size="xs" label="Habilitada" checked={c.enabled ?? true} onChange={(e) => set({ enabled: e.currentTarget.checked })} />
        <Switch size="xs" label="Validar datos existentes" disabled={c.enabled === false} checked={c.validated ?? true} onChange={(e) => set({ validated: e.currentTarget.checked })} />
        <Switch size="xs" label="DEFERRABLE" checked={!!c.deferrable} onChange={(e) => set({ deferrable: e.currentTarget.checked, initially_deferred: e.currentTarget.checked ? c.initially_deferred : false })} />
        <Switch size="xs" label="INITIALLY DEFERRED" disabled={!c.deferrable} checked={!!c.initially_deferred} onChange={(e) => set({ initially_deferred: e.currentTarget.checked })} />
        {(c.kind === "PRIMARY_KEY" || c.kind === "UNIQUE") && (
          <TextInput size="xs" placeholder="Tablespace del índice" value={c.index_tablespace ?? ""} onChange={(e) => set({ index_tablespace: up(e.currentTarget.value) || null })} w={180} />
        )}
      </Group>
    </Stack>
  );
}

// ------------------------------------------------------------------ particionado
function PartitioningEditor({ pt, onChange, columnOptions }: { pt: Partitioning; onChange: (p: Partitioning | null) => void; columnOptions: string[] }) {
  const set = (patch: Partial<Partitioning>) => onChange({ ...pt, ...patch });
  const setPart = (i: number, p: PartitionDef) => set({ partitions: pt.partitions.map((x, j) => (j === i ? p : x)) });
  const hint = pt.method === "RANGE" ? "Límite superior (exclusivo): DATE '2026-01-01', 1000 o MAXVALUE" : "Valores: 'NORTE','SUR' o DEFAULT";
  return (
    <Stack gap="xs">
      <Group align="flex-end">
        <SegmentedControl size="xs" data={["RANGE", "LIST", "HASH"]} value={pt.method} onChange={(v) => set({ method: v as Partitioning["method"], interval: v === "RANGE" ? pt.interval : null })} />
        <TagsInput size="xs" label="Columnas de partición" data={columnOptions} value={pt.columns} onChange={(v) => set({ columns: v.map(up) })} w={280} />
        {pt.method === "RANGE" && (
          <TextInput size="xs" label="INTERVAL (opcional, crea particiones solas)" placeholder="NUMTOYMINTERVAL(1,'MONTH')" value={pt.interval ?? ""} onChange={(e) => set({ interval: e.currentTarget.value || null })} w={300} />
        )}
        {pt.method === "HASH" && (
          <NumberInput size="xs" label="Número de particiones" description="o defínelas por nombre abajo" value={pt.hash_count ?? ""} min={1} max={1024} onChange={(v) => set({ hash_count: num(v) })} w={180} />
        )}
      </Group>
      {pt.partitions.map((p, i) => (
        <Group key={i} gap="xs" align="flex-end">
          <TextInput size="xs" label={i === 0 ? "Partición" : undefined} value={p.name} onChange={(e) => setPart(i, { ...p, name: up(e.currentTarget.value) })} w={160} />
          {pt.method !== "HASH" && (
            <TextInput size="xs" label={i === 0 ? (pt.method === "RANGE" ? "VALUES LESS THAN (…)" : "VALUES (…)") : undefined} placeholder={hint} value={p.values ?? ""}
              onChange={(e) => setPart(i, { ...p, values: e.currentTarget.value })} style={{ flex: 1 }} styles={{ input: { fontFamily: "monospace" } }} />
          )}
          <TextInput size="xs" label={i === 0 ? "Tablespace" : undefined} value={p.tablespace ?? ""} onChange={(e) => setPart(i, { ...p, tablespace: up(e.currentTarget.value) || null })} w={140} />
          <ActionIcon variant="subtle" color="red" onClick={() => set({ partitions: pt.partitions.filter((_, j) => j !== i) })}><IconTrash size={14} /></ActionIcon>
        </Group>
      ))}
      <Group>
        <Button size="compact-xs" variant="light" leftSection={<IconPlus size={12} />} onClick={() => set({ partitions: [...pt.partitions, { name: `P${pt.partitions.length + 1}`, values: "" }] })}>Partición</Button>
        <Button size="compact-xs" variant="subtle" color="red" onClick={() => onChange(null)}>Quitar particionado</Button>
      </Group>
    </Stack>
  );
}

// ------------------------------------------------------------------ crear tabla
function CreateTableEditor({ op, onChange, connectionId, meta }: EditorProps) {
  const columns = (op.columns as Col[]) ?? [];
  const pk = (op.primary_key as string[]) ?? [];
  const constraints = (op.constraints as ConstraintDef[]) ?? [];
  const pt = op.partitioning as Partitioning | null | undefined;
  const [open, setOpen] = useState<Record<number, boolean>>({});
  const set = (patch: Record<string, unknown>) => onChange({ ...op, ...patch });
  const names = columns.map((c) => c.name).filter(Boolean);
  const setCol = (i: number, c: Col) => {
    const next = [...columns];
    const oldName = next[i].name;
    next[i] = c;
    set({ columns: next, primary_key: pk.map((p) => (p === oldName ? c.name : p)) });
  };
  return (
    <Stack gap="sm">
      <SimpleGrid cols={{ base: 1, sm: 3 }}>
        <SchemaInput connectionId={connectionId} label="Esquema (owner)" value={s(op.schema)} onChange={(v) => set({ schema: v })} />
        <TextInput label="Nombre de la tabla" value={s(op.table)} onChange={(e) => set({ table: up(e.currentTarget.value) })} required />
        <TextInput label="Comentario de la tabla" description="Qué guarda; queda documentado en la base" value={s(op.comment)} onChange={(e) => set({ comment: e.currentTarget.value })} />
      </SimpleGrid>

      <Divider label="Columnas" labelPosition="left" />
      <ScrollArea>
        <Table miw={1000} verticalSpacing={4}>
          <Table.Thead>
            <Table.Tr>
              <Table.Th w={28} />
              <Table.Th w={36}>PK</Table.Th>
              <Table.Th>Columna</Table.Th>
              <Table.Th w={190}>Tipo</Table.Th>
              <Table.Th w={90}>Long./Prec.</Table.Th>
              <Table.Th w={80}>Escala</Table.Th>
              <Table.Th w={50}>Nulos</Table.Th>
              <Table.Th>Default</Table.Th>
              <Table.Th>Comentario</Table.Th>
              <Table.Th w={36} />
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {columns.map((c, i) => (
              <Fragment key={i}>
                <Table.Tr>
                  <Table.Td>
                    <Tooltip label="Opciones avanzadas (identidad, virtual, invisible…)">
                      <ActionIcon size="sm" variant={colBadges(c).length ? "light" : "subtle"} onClick={() => setOpen({ ...open, [i]: !open[i] })}>
                        {open[i] ? <IconChevronDown size={14} /> : <IconChevronRight size={14} />}
                      </ActionIcon>
                    </Tooltip>
                  </Table.Td>
                  <Table.Td>
                    <Checkbox size="xs" checked={pk.includes(c.name)} onChange={(e) => set({ primary_key: e.currentTarget.checked ? [...pk, c.name] : pk.filter((p) => p !== c.name), columns: e.currentTarget.checked ? columns.map((x, j) => (j === i ? { ...x, nullable: false } : x)) : columns })} />
                  </Table.Td>
                  {columnFieldNodes({ col: c, onChange: (nc) => setCol(i, nc), meta, compact: true }).map((cell, k) => (
                    <Table.Td key={k}>
                      {cell}
                      {k === 0 && colBadges(c).length > 0 && <Group gap={2} mt={2}>{colBadges(c).map((b) => <Badge key={b} size="xs" variant="light">{b}</Badge>)}</Group>}
                    </Table.Td>
                  ))}
                  <Table.Td>
                    <ActionIcon variant="subtle" color="red" onClick={() => set({ columns: columns.filter((_, j) => j !== i), primary_key: pk.filter((p) => p !== c.name) })}>
                      <IconTrash size={16} />
                    </ActionIcon>
                  </Table.Td>
                </Table.Tr>
                {open[i] && (
                  <Table.Tr>
                    <Table.Td />
                    <Table.Td colSpan={9} pb="sm"><ColumnAdvanced col={c} onChange={(nc) => setCol(i, nc)} /></Table.Td>
                  </Table.Tr>
                )}
              </Fragment>
            ))}
          </Table.Tbody>
        </Table>
      </ScrollArea>
      <Group>
        <Button size="xs" variant="light" leftSection={<IconPlus size={14} />} onClick={() => set({ columns: [...columns, { name: "", data_type: "VARCHAR2", length: 100, nullable: true }] })}>Agregar columna</Button>
        <Button size="xs" variant="subtle" onClick={() => set({ columns: [...columns,
          { name: "FECHA_CREACION", data_type: "TIMESTAMP", nullable: false, default: "SYSTIMESTAMP", comment: "Fecha de alta del registro" },
          { name: "USUARIO_CREACION", data_type: "VARCHAR2", length: 100, nullable: false, default: "USER", comment: "Usuario que creó el registro" },
        ] })}>+ columnas de auditoría</Button>
      </Group>
      {pk.length > 0 && (
        <Group gap="xs">
          <Text size="sm">Llave primaria: <b>{pk.join(", ")}</b></Text>
          <TextInput size="xs" placeholder={`Nombre (vacío = PK_${s(op.table) || "TABLA"})`} value={s(op.primary_key_name)} onChange={(e) => set({ primary_key_name: up(e.currentTarget.value) || null })} w={240} />
          <TextInput size="xs" placeholder="Tablespace del índice" value={s(op.primary_key_tablespace)} onChange={(e) => set({ primary_key_tablespace: up(e.currentTarget.value) || null })} w={180} />
        </Group>
      )}

      <Divider label={`Restricciones (${constraints.length})`} labelPosition="left" />
      {constraints.map((c, i) => (
        <Card key={i} withBorder p="xs">
          <Group justify="flex-end" mb={-28}>
            <ActionIcon variant="subtle" color="red" onClick={() => set({ constraints: constraints.filter((_, j) => j !== i) })}><IconTrash size={14} /></ActionIcon>
          </Group>
          <ConstraintEditor c={c} allowPk={false} connectionId={connectionId} schema={s(op.schema)} table={s(op.table)} columnOptions={names}
            onChange={(nc) => set({ constraints: constraints.map((x, j) => (j === i ? nc : x)) })} />
        </Card>
      ))}
      <Group gap="xs">
        {(["UNIQUE", "FOREIGN_KEY", "CHECK"] as const).map((k) => (
          <Button key={k} size="compact-xs" variant="light" leftSection={<IconPlus size={12} />} onClick={() => set({ constraints: [...constraints, { kind: k, columns: [] }] })}>{KIND_LABEL[k]}</Button>
        ))}
      </Group>

      <Divider label="Opciones de tabla" labelPosition="left" />
      <SimpleGrid cols={{ base: 1, sm: 4 }}>
        <Select label="Tipo de tabla" data={[{ value: "", label: "Normal" }, { value: "DELETE_ROWS", label: "Temporal (se vacía al COMMIT)" }, { value: "PRESERVE_ROWS", label: "Temporal (se vacía al cerrar sesión)" }]}
          value={s(op.temporary)} onChange={(v) => set({ temporary: v || null, ...(v ? { partitioning: null, tablespace: null, compression: null } : {}) })} allowDeselect={false} />
        <TextInput label="Tablespace" disabled={!!op.temporary} value={s(op.tablespace)} onChange={(e) => set({ tablespace: up(e.currentTarget.value) || null })} />
        <Select label="Compresión" disabled={!!op.temporary} data={[{ value: "", label: "(por defecto)" }, { value: "NONE", label: "Sin compresión" }, { value: "BASIC", label: "Básica (cargas directas)" }, { value: "ADVANCED", label: "Avanzada (requiere licencia)" }]}
          value={s(op.compression)} onChange={(v) => set({ compression: v || null })} allowDeselect={false} />
        <Select label="Logging" data={[{ value: "", label: "(por defecto)" }, { value: "Y", label: "LOGGING" }, { value: "N", label: "NOLOGGING" }]}
          value={op.logging === true ? "Y" : op.logging === false ? "N" : ""} onChange={(v) => set({ logging: v === "Y" ? true : v === "N" ? false : null })} allowDeselect={false} />
      </SimpleGrid>

      <Divider label="Particionado" labelPosition="left" />
      {pt ? (
        <PartitioningEditor pt={pt} columnOptions={names} onChange={(p) => set({ partitioning: p })} />
      ) : (
        <Group>
          <Button size="compact-xs" variant="light" disabled={!!op.temporary} leftSection={<IconPlus size={12} />}
            onClick={() => set({ partitioning: { method: "RANGE", columns: [], interval: null, partitions: [{ name: "P1", values: "" }] } })}>
            Particionar la tabla
          </Button>
          {!!op.temporary && <Text size="xs" c="dimmed">Las tablas temporales no se particionan</Text>}
        </Group>
      )}
    </Stack>
  );
}

// ------------------------------------------------------------------ columnas en tablas existentes
export function catalogToCol(c: { name: string; data_type: string; length: number | null; char_semantics: boolean; precision: number | null; scale: number | null; nullable: boolean; default_value: string | null; default_on_null: boolean; comments: string | null; identity: boolean; identity_mode?: Col["identity_mode"]; identity_start?: number | null; identity_increment?: number | null; virtual_expression: string | null; invisible: boolean }): Col {
  return {
    name: c.name, data_type: c.data_type, length: c.length, char_semantics: c.char_semantics, precision: c.precision, scale: c.scale,
    nullable: c.nullable, default: c.default_value, default_on_null: c.default_on_null, comment: c.comments, identity: c.identity,
    identity_mode: c.identity_mode ?? "BY_DEFAULT_ON_NULL", identity_start: c.identity_start ?? null, identity_increment: c.identity_increment ?? null,
    virtual_expression: c.virtual_expression, invisible: c.invisible,
  };
}

function TableColumnEditor({ op, onChange, connectionId, meta }: EditorProps) {
  const set = (patch: Record<string, unknown>) => onChange({ ...op, ...patch });
  const modify = op.type === "MODIFY_COLUMN";
  const { data: table } = useTable(connectionId, s(op.schema) || null, s(op.table) || null);
  const col = (op.column as Col) ?? { name: "", data_type: "VARCHAR2", nullable: true };
  const prev = op.previous as Col | null;
  return (
    <Stack gap="sm">
      <SimpleGrid cols={{ base: 1, sm: 3 }}>
        <TableBase op={op} set={set} connectionId={connectionId} />
        {modify && (
          <Select label="Columna a modificar" data={(table?.columns ?? []).map((c) => c.name)} value={col.name || null} searchable
            onChange={(v) => {
              const c = table?.columns.find((x) => x.name === v);
              if (!c) return;
              const current = catalogToCol(c);
              set({ column: current, previous: current });
            }} />
        )}
      </SimpleGrid>
      {modify && prev && (
        <Text size="xs" c="dimmed">
          Definición actual: {table?.columns.find((c) => c.name === prev.name)?.type_display}{prev.nullable ? "" : " NOT NULL"}
          {prev.default ? ` DEFAULT ${prev.default}` : ""}. Solo se genera SQL para lo que cambies.
          {prev.virtual_expression ? " Es virtual: para cambiar su expresión hay que eliminarla y crearla de nuevo." : ""}
        </Text>
      )}
      <SimpleGrid cols={{ base: 1, sm: 4 }}>
        <ColumnFields col={col} meta={meta} onChange={(c) => set({ column: modify ? { ...c, name: col.name } : c })} />
      </SimpleGrid>
      <ColumnAdvanced col={col} onChange={(c) => set({ column: c })} />
    </Stack>
  );
}

function DropColumnEditor({ op, onChange, connectionId }: EditorProps) {
  const set = (patch: Record<string, unknown>) => onChange({ ...op, ...patch });
  const { data: table } = useTable(connectionId, s(op.schema) || null, s(op.table) || null);
  return (
    <SimpleGrid cols={{ base: 1, sm: 3 }}>
      <TableBase op={op} set={set} connectionId={connectionId} />
      <Select label="Columna" data={(table?.columns ?? []).map((c) => c.name)} value={s(op.column_name) || null} searchable onChange={(v) => set({ column_name: v })} />
    </SimpleGrid>
  );
}

function DropTableEditor({ op, onChange, connectionId }: EditorProps) {
  const set = (patch: Record<string, unknown>) => onChange({ ...op, ...patch });
  return (
    <Stack gap="sm">
      <SimpleGrid cols={{ base: 1, sm: 2 }}>
        <TableBase op={op} set={set} connectionId={connectionId} />
      </SimpleGrid>
      <Group>
        <Switch label="CASCADE CONSTRAINTS (elimina FKs que la referencian)" checked={!!op.cascade_constraints} onChange={(e) => set({ cascade_constraints: e.currentTarget.checked })} />
        <Switch label="PURGE (no pasa por la papelera, irreversible)" color="red" checked={!!op.purge} onChange={(e) => set({ purge: e.currentTarget.checked })} />
      </Group>
    </Stack>
  );
}

function AlterTableOptionsEditor({ op, onChange, connectionId }: EditorProps) {
  const set = (patch: Record<string, unknown>) => onChange({ ...op, ...patch });
  const tri = (v: unknown, yes: string, no: string) => (v === true ? yes : v === false ? no : "");
  return (
    <SimpleGrid cols={{ base: 1, sm: 3 }}>
      <TableBase op={op} set={set} connectionId={connectionId} />
      <div />
      <Select label="Logging" data={[{ value: "", label: "(sin cambio)" }, { value: "Y", label: "LOGGING" }, { value: "N", label: "NOLOGGING" }]}
        value={tri(op.logging, "Y", "N")} onChange={(v) => set({ logging: v === "Y" ? true : v === "N" ? false : null })} allowDeselect={false} />
      <Select label="Compresión (datos futuros)" data={[{ value: "", label: "(sin cambio)" }, { value: "NONE", label: "Sin compresión" }, { value: "BASIC", label: "Básica" }, { value: "ADVANCED", label: "Avanzada (licencia)" }]}
        value={s(op.compression)} onChange={(v) => set({ compression: v || null })} allowDeselect={false} />
      <Select label="Acceso" data={[{ value: "", label: "(sin cambio)" }, { value: "Y", label: "Solo lectura (READ ONLY)" }, { value: "N", label: "Lectura/escritura (READ WRITE)" }]}
        value={tri(op.read_only, "Y", "N")} onChange={(v) => set({ read_only: v === "Y" ? true : v === "N" ? false : null })} allowDeselect={false} />
    </SimpleGrid>
  );
}

function RenameEditor({ op, onChange, connectionId }: EditorProps) {
  const set = (patch: Record<string, unknown>) => onChange({ ...op, ...patch });
  const target = s(op.target) || "TABLE";
  const { data: table } = useTable(connectionId, s(op.schema) || null, target === "TABLE" ? null : s(op.table) || null);
  const options = target === "COLUMN" ? table?.columns.map((c) => c.name) : target === "CONSTRAINT" ? table?.constraints.map((c) => c.name) : target === "INDEX" ? table?.indexes.map((i) => i.name) : undefined;
  return (
    <Stack gap="sm">
      <SegmentedControl data={[{ value: "TABLE", label: "Tabla" }, { value: "COLUMN", label: "Columna" }, { value: "CONSTRAINT", label: "Restricción" }, { value: "INDEX", label: "Índice" }]}
        value={target} onChange={(v) => set({ target: v, old_name: "", table: v === "TABLE" ? null : op.table })} />
      <SimpleGrid cols={{ base: 1, sm: 4 }}>
        <SchemaInput connectionId={connectionId} label="Esquema" value={s(op.schema)} onChange={(v) => set({ schema: v })} />
        {target === "TABLE" ? (
          <ObjectInput connectionId={connectionId} schema={s(op.schema)} label="Tabla actual" types={["TABLE"]} value={s(op.old_name)} onChange={(v) => set({ old_name: v })} />
        ) : (
          <>
            <ObjectInput connectionId={connectionId} schema={s(op.schema)} label="Tabla" types={["TABLE"]} value={s(op.table)} onChange={(v) => set({ table: v })} />
            <Autocomplete label="Nombre actual" data={options ?? []} value={s(op.old_name)} onChange={(v) => set({ old_name: up(v) })} required />
          </>
        )}
        <TextInput label="Nombre nuevo" value={s(op.new_name)} onChange={(e) => set({ new_name: up(e.currentTarget.value) })} required />
      </SimpleGrid>
      <Text size="xs" c="dimmed">Vistas, procedimientos y sinónimos que usen el nombre anterior quedarán inválidos; la validación los lista.</Text>
    </Stack>
  );
}

function SetCommentEditor({ op, onChange, connectionId }: EditorProps) {
  const set = (patch: Record<string, unknown>) => onChange({ ...op, ...patch });
  const { data: table } = useTable(connectionId, s(op.schema) || null, s(op.table) || null);
  return (
    <Stack gap="sm">
      <SimpleGrid cols={{ base: 1, sm: 3 }}>
        <TableBase op={op} set={set} connectionId={connectionId} tableTypes={["TABLE", "VIEW", "MATERIALIZED VIEW"]} />
        <Select label="Columna" placeholder="(comentario de la tabla)" clearable data={(table?.columns ?? []).map((c) => c.name)} value={s(op.column) || null}
          onChange={(v) => set({ column: v, comment: v ? table?.columns.find((c) => c.name === v)?.comments ?? "" : table?.comments ?? "" })} />
      </SimpleGrid>
      <Textarea label="Comentario" description="Vacío elimina el comentario" value={s(op.comment)} onChange={(e) => set({ comment: e.currentTarget.value })} autosize minRows={2} />
    </Stack>
  );
}

// ------------------------------------------------------------------ restricciones en tablas existentes
function AddConstraintEditor({ op, onChange, connectionId }: EditorProps) {
  const set = (patch: Record<string, unknown>) => onChange({ ...op, ...patch });
  return (
    <Stack gap="sm">
      <SimpleGrid cols={{ base: 1, sm: 2 }}>
        <TableBase op={op} set={set} connectionId={connectionId} />
      </SimpleGrid>
      <ConstraintEditor c={(op.constraint as ConstraintDef) ?? { kind: "UNIQUE", columns: [] }} connectionId={connectionId} schema={s(op.schema)} table={s(op.table)} onChange={(c) => set({ constraint: c })} />
    </Stack>
  );
}

function ConstraintPickEditor({ op, onChange, connectionId }: EditorProps) {
  const set = (patch: Record<string, unknown>) => onChange({ ...op, ...patch });
  const { data: table } = useTable(connectionId, s(op.schema) || null, s(op.table) || null);
  const con = table?.constraints.find((c) => c.name === op.constraint_name);
  const drop = op.type === "DROP_CONSTRAINT";
  return (
    <Stack gap="sm">
      <SimpleGrid cols={{ base: 1, sm: 3 }}>
        <TableBase op={op} set={set} connectionId={connectionId} />
        <Select label="Restricción" searchable data={(table?.constraints ?? []).map((c) => ({ value: c.name, label: `${c.name} (${({ P: "PK", U: "única", R: "FK", C: "check" })[c.type]}${c.enabled ? "" : ", deshabilitada"})` }))}
          value={s(op.constraint_name) || null} onChange={(v) => set({ constraint_name: v })} />
      </SimpleGrid>
      {con && <Text size="xs" c="dimmed">{con.columns || con.condition}{con.ref_table ? ` → ${con.ref_owner}.${con.ref_table}` : ""}</Text>}
      {drop ? (
        <Group>
          <Switch label="CASCADE (elimina también las FK que dependan de ella)" checked={!!op.cascade} onChange={(e) => set({ cascade: e.currentTarget.checked })} />
          {(!con || con.type === "P" || con.type === "U") && (
            <Select label="Índice que la soporta" w={220} data={[{ value: "", label: "(comportamiento por defecto)" }, { value: "KEEP", label: "Conservar índice" }, { value: "DROP", label: "Eliminar índice" }]}
              value={s(op.index)} onChange={(v) => set({ index: v || null })} allowDeselect={false} />
          )}
        </Group>
      ) : (
        <Group>
          <SegmentedControl data={[{ value: "ENABLE", label: "Habilitar" }, { value: "DISABLE", label: "Deshabilitar" }]} value={s(op.action) || "ENABLE"} onChange={(v) => set({ action: v })} />
          {op.action !== "DISABLE" && <Switch label="Validar datos existentes (si no, NOVALIDATE)" checked={op.validate_existing !== false} onChange={(e) => set({ validate_existing: e.currentTarget.checked })} />}
        </Group>
      )}
    </Stack>
  );
}

// ------------------------------------------------------------------ índices
type IdxCol = { expression: string; descending?: boolean };

function CreateIndexEditor({ op, onChange, connectionId }: EditorProps) {
  const set = (patch: Record<string, unknown>) => onChange({ ...op, ...patch });
  const { data: table } = useTable(connectionId, s(op.schema) || null, s(op.table) || null);
  const cols = (op.columns as IdxCol[]) ?? [];
  const kind = s(op.index_type) || "NORMAL";
  const domain = kind === "DOMAIN";
  const partitioned = table?.partitioned === "YES";
  const setCol = (i: number, c: IdxCol) => set({ columns: cols.map((x, j) => (j === i ? c : x)) });
  return (
    <Stack gap="sm">
      <SimpleGrid cols={{ base: 1, sm: 3 }}>
        <TableBase op={op} set={set} connectionId={connectionId} />
        <TextInput label="Nombre del índice" value={s(op.index_name)} onChange={(e) => set({ index_name: up(e.currentTarget.value) })} required />
      </SimpleGrid>
      <SegmentedControl data={[{ value: "NORMAL", label: "Normal (B-tree)" }, { value: "UNIQUE", label: "Único" }, { value: "BITMAP", label: "Bitmap" }, { value: "DOMAIN", label: "Dominio (texto, espacial…)" }]}
        value={kind} onChange={(v) => set({ index_type: v, ...(v === "DOMAIN" ? { reverse: false, compression: null, local: false, online: false, columns: cols.slice(0, 1).map((c) => ({ ...c, descending: false })) } : { indextype: null, parameters: null }), ...(v === "BITMAP" ? { reverse: false } : {}) })} />
      <Text size="sm" fw={500}>Columnas o expresiones (en orden)</Text>
      {cols.map((c, i) => (
        <Group key={i} gap="xs">
          <Autocomplete size="xs" style={{ flex: 1 }} data={(table?.columns ?? []).map((x) => x.name)} value={c.expression}
            placeholder="COLUMNA o expresión, p. ej. UPPER(EMAIL)" onChange={(v) => setCol(i, { ...c, expression: v })} styles={{ input: { fontFamily: "monospace" } }} />
          {!domain && <SegmentedControl size="xs" data={["ASC", "DESC"]} value={c.descending ? "DESC" : "ASC"} onChange={(v) => setCol(i, { ...c, descending: v === "DESC" })} />}
          <ActionIcon variant="subtle" color="red" onClick={() => set({ columns: cols.filter((_, j) => j !== i) })}><IconTrash size={14} /></ActionIcon>
        </Group>
      ))}
      {!(domain && cols.length >= 1) && (
        <Group><Button size="compact-xs" variant="light" leftSection={<IconPlus size={12} />} onClick={() => set({ columns: [...cols, { expression: "", descending: false }] })}>Columna</Button></Group>
      )}
      {domain ? (
        <SimpleGrid cols={{ base: 1, sm: 2 }}>
          <TextInput label="INDEXTYPE" placeholder="CTXSYS.CONTEXT, MDSYS.SPATIAL_INDEX_V2…" value={s(op.indextype)} onChange={(e) => set({ indextype: e.currentTarget.value.toUpperCase().replace(/\s/g, "") })} required />
          <TextInput label="PARAMETERS (opcional)" placeholder="SYNC (ON COMMIT)" value={s(op.parameters)} onChange={(e) => set({ parameters: e.currentTarget.value || null })} />
        </SimpleGrid>
      ) : (
        <SimpleGrid cols={{ base: 1, sm: 4 }}>
          <TextInput label="Tablespace" value={s(op.tablespace)} onChange={(e) => set({ tablespace: up(e.currentTarget.value) || null })} />
          <Select label="Compresión" data={[{ value: "", label: "Sin compresión" }, { value: "PREFIX", label: "Por prefijo (COMPRESS n)" }, { value: "ADVANCED_LOW", label: "Avanzada LOW (licencia)" }, { value: "ADVANCED_HIGH", label: "Avanzada HIGH (licencia)" }]}
            value={s(op.compression)} onChange={(v) => set({ compression: v || null, prefix_length: v === "PREFIX" ? op.prefix_length : null })} allowDeselect={false} />
          {op.compression === "PREFIX" ? (
            <NumberInput label="Columnas de prefijo" description="vacío = automático" min={1} max={cols.length} value={(op.prefix_length as number) ?? ""} onChange={(v) => set({ prefix_length: num(v) })} />
          ) : <div />}
          <div />
          <Switch label="LOCAL (por partición)" disabled={!partitioned && !op.local} description={partitioned ? undefined : "Requiere tabla particionada"} checked={!!op.local} onChange={(e) => set({ local: e.currentTarget.checked })} />
          <Switch label="REVERSE (evita contención en secuencias)" disabled={kind === "BITMAP"} checked={!!op.reverse} onChange={(e) => set({ reverse: e.currentTarget.checked })} />
          <Switch label="Invisible (el optimizador no lo usa)" checked={!!op.invisible} onChange={(e) => set({ invisible: e.currentTarget.checked })} />
          <Switch label="ONLINE (sin bloquear la tabla)" checked={!!op.online} onChange={(e) => set({ online: e.currentTarget.checked })} />
        </SimpleGrid>
      )}
    </Stack>
  );
}

function IndexPickEditor({ op, onChange, connectionId }: EditorProps) {
  const set = (patch: Record<string, unknown>) => onChange({ ...op, ...patch });
  const { data: table } = useTable(connectionId, s(op.schema) || null, s(op.table) || null);
  const idx = table?.indexes.find((i) => i.name === op.index_name);
  const alter = op.type === "ALTER_INDEX";
  return (
    <Stack gap="sm">
      <SimpleGrid cols={{ base: 1, sm: 3 }}>
        <TableBase op={op} set={set} connectionId={connectionId} />
        <Autocomplete label="Índice" data={(table?.indexes ?? []).map((i) => i.name)} value={s(op.index_name)} onChange={(v) => set({ index_name: up(v) })} required />
      </SimpleGrid>
      {idx && <Text size="xs" c="dimmed">{idx.kind} ({idx.columns}) · {idx.visibility}{idx.constraint_name ? ` · soporta la restricción ${idx.constraint_name}` : ""}{idx.partitioned === "YES" ? " · particionado" : ""}</Text>}
      {alter ? (
        <Group>
          <SegmentedControl data={[{ value: "REBUILD", label: "Reconstruir" }, { value: "VISIBLE", label: "Hacer visible" }, { value: "INVISIBLE", label: "Hacer invisible" }]} value={s(op.action) || "REBUILD"} onChange={(v) => set({ action: v })} />
          {(op.action ?? "REBUILD") === "REBUILD" && (
            <>
              <Switch label="ONLINE" checked={!!op.online} onChange={(e) => set({ online: e.currentTarget.checked })} />
              <TextInput placeholder="Mover a tablespace (opcional)" value={s(op.tablespace)} onChange={(e) => set({ tablespace: up(e.currentTarget.value) || null })} w={220} />
            </>
          )}
        </Group>
      ) : (
        <Switch label="ONLINE (sin bloquear la tabla)" checked={!!op.online} onChange={(e) => set({ online: e.currentTarget.checked })} />
      )}
    </Stack>
  );
}

// ------------------------------------------------------------------ particiones en tablas existentes
function PartitionOpEditor({ op, onChange, connectionId }: EditorProps) {
  const set = (patch: Record<string, unknown>) => onChange({ ...op, ...patch });
  const { data: table } = useTable(connectionId, s(op.schema) || null, s(op.table) || null);
  const add = op.type === "ADD_PARTITION";
  const part = (op.partition as PartitionDef) ?? { name: "" };
  const method = table?.partitioning?.partitioning_type || s(op.method) || "RANGE";
  return (
    <Stack gap="sm">
      <SimpleGrid cols={{ base: 1, sm: 3 }}>
        <TableBase op={op} set={set} connectionId={connectionId} />
        {table?.partitioning ? (
          <Text size="sm" mt={28}>Particionada por <b>{table.partitioning.partitioning_type}</b> ({table.partitioning.columns}){table.partitioning.interval ? ` · INTERVAL ${table.partitioning.interval}` : ""}</Text>
        ) : table ? <Text size="sm" c="red" mt={28}>La tabla no está particionada</Text> : <div />}
      </SimpleGrid>
      {add ? (
        <SimpleGrid cols={{ base: 1, sm: 3 }}>
          <TextInput label="Nombre de la partición" value={part.name} onChange={(e) => set({ method, partition: { ...part, name: up(e.currentTarget.value) } })} required />
          {method !== "HASH" && (
            <TextInput label={method === "RANGE" ? "VALUES LESS THAN (…)" : "VALUES (…)"} value={part.values ?? ""} styles={{ input: { fontFamily: "monospace" } }}
              placeholder={method === "RANGE" ? "DATE '2027-01-01'" : "'OESTE'"} onChange={(e) => set({ method, partition: { ...part, values: e.currentTarget.value } })} />
          )}
          <TextInput label="Tablespace" value={part.tablespace ?? ""} onChange={(e) => set({ method, partition: { ...part, tablespace: up(e.currentTarget.value) || null } })} />
        </SimpleGrid>
      ) : (
        <Group align="flex-end">
          <Select label="Partición" searchable data={(table?.partitions ?? []).map((p) => ({ value: p.name, label: `${p.name}${p.high_value ? ` (${p.high_value.slice(0, 40)})` : ""}` }))}
            value={s(op.partition_name) || null} onChange={(v) => set({ partition_name: v })} w={360} />
          <Switch label="Actualizar índices globales (UPDATE INDEXES)" checked={op.update_indexes !== false} onChange={(e) => set({ update_indexes: e.currentTarget.checked })} />
        </Group>
      )}
      {!add && <Text size="xs" c="red">Eliminar una partición borra sus datos.</Text>}
    </Stack>
  );
}

// ------------------------------------------------------------------ secuencias y logs de vista materializada
function SequenceEditor({ op, onChange, connectionId }: EditorProps) {
  const set = (patch: Record<string, unknown>) => onChange({ ...op, ...patch });
  const create = op.type === "CREATE_SEQUENCE";
  const tri = (v: unknown) => (v === true ? "Y" : v === false ? "N" : "");
  return (
    <SimpleGrid cols={{ base: 1, sm: 4 }}>
      <SchemaInput connectionId={connectionId} label="Esquema" value={s(op.schema)} onChange={(v) => set({ schema: v })} />
      {create ? (
        <TextInput label="Nombre de la secuencia" value={s(op.name)} onChange={(e) => set({ name: up(e.currentTarget.value) })} required />
      ) : (
        <ObjectInput connectionId={connectionId} schema={s(op.schema)} label="Secuencia" types={["SEQUENCE"]} value={s(op.name)} onChange={(v) => set({ name: v })} />
      )}
      {create ? (
        <NumberInput label="Inicia en" value={(op.start_with as number) ?? ""} onChange={(v) => set({ start_with: num(v) })} />
      ) : (
        <NumberInput label="Reiniciar en (RESTART)" description="vacío = no reiniciar" value={(op.restart_with as number) ?? ""} onChange={(v) => set({ restart_with: num(v) })} />
      )}
      <NumberInput label="Incremento" value={(op.increment_by as number) ?? ""} onChange={(v) => set({ increment_by: num(v) })} />
      <NumberInput label="Mínimo" value={(op.min_value as number) ?? ""} onChange={(v) => set({ min_value: num(v) })} />
      <NumberInput label="Máximo" value={(op.max_value as number) ?? ""} onChange={(v) => set({ max_value: num(v) })} />
      <NumberInput label="Cache" description="0 = NOCACHE" min={0} value={(op.cache as number) ?? ""} onChange={(v) => set({ cache: num(v) })} />
      <Group grow>
        <Select label="Ciclo" data={[{ value: "", label: create ? "No" : "(sin cambio)" }, { value: "Y", label: "CYCLE" }, { value: "N", label: "NOCYCLE" }]} value={tri(op.cycle)} onChange={(v) => set({ cycle: v === "Y" ? true : v === "N" ? false : null })} allowDeselect={false} />
        <Select label="Orden" data={[{ value: "", label: create ? "No" : "(sin cambio)" }, { value: "Y", label: "ORDER" }, { value: "N", label: "NOORDER" }]} value={tri(op.order)} onChange={(v) => set({ order: v === "Y" ? true : v === "N" ? false : null })} allowDeselect={false} />
      </Group>
    </SimpleGrid>
  );
}

function MvLogEditor({ op, onChange, connectionId }: EditorProps) {
  const set = (patch: Record<string, unknown>) => onChange({ ...op, ...patch });
  const create = op.type === "CREATE_MV_LOG";
  return (
    <Stack gap="sm">
      <SimpleGrid cols={{ base: 1, sm: 2 }}>
        <TableBase op={op} set={set} connectionId={connectionId} />
      </SimpleGrid>
      {create && (
        <>
          <Group>
            <Switch label="WITH PRIMARY KEY" checked={op.with_primary_key !== false} onChange={(e) => set({ with_primary_key: e.currentTarget.checked })} />
            <Switch label="WITH ROWID" checked={!!op.with_rowid} onChange={(e) => set({ with_rowid: e.currentTarget.checked })} />
            <Switch label="WITH SEQUENCE" checked={!!op.with_sequence} onChange={(e) => set({ with_sequence: e.currentTarget.checked })} />
            <Switch label="INCLUDING NEW VALUES" checked={!!op.including_new_values} onChange={(e) => set({ including_new_values: e.currentTarget.checked })} />
          </Group>
          <ColumnsInput connectionId={connectionId} schema={s(op.schema)} table={s(op.table)} label="Columnas adicionales (para refresco FAST con agregados)" value={(op.columns as string[]) ?? []} onChange={(v) => set({ columns: v })} />
        </>
      )}
    </Stack>
  );
}

// ------------------------------------------------------------------ permisos y sinónimos
function GrantEditor({ op, onChange, connectionId, meta }: EditorProps) {
  const set = (patch: Record<string, unknown>) => onChange({ ...op, ...patch });
  const { data: schemas } = useSchemas(connectionId);
  const privileges = (op.privileges as string[]) ?? [];
  const columnable = op.type === "GRANT" && privileges.length > 0 && privileges.every((p) => ["INSERT", "UPDATE", "REFERENCES"].includes(p));
  return (
    <SimpleGrid cols={{ base: 1, sm: 2 }}>
      <SchemaInput connectionId={connectionId} label="Esquema dueño del objeto" value={s(op.schema)} onChange={(v) => set({ schema: v })} />
      <ObjectInput connectionId={connectionId} schema={s(op.schema)} label="Objeto (tabla, vista, procedimiento…)" description="Puede ser un objeto que se crea en esta misma solicitud" value={s(op.object)} onChange={(v) => set({ object: v })} />
      <Autocomplete label={op.type === "GRANT" ? "Otorgar a (usuario o rol)" : "Revocar a (usuario o rol)"} description="Si el usuario no existe, agrega antes una operación Crear usuario"
        data={(schemas ?? []).map((x) => x.name)} value={s(op.grantee)} onChange={(v) => set({ grantee: up(v) })} required />
      <MultiSelect label="Privilegios" data={meta.object_privileges} value={privileges} onChange={(v) => set({ privileges: v, columns: v.every((p) => ["INSERT", "UPDATE", "REFERENCES"].includes(p)) ? op.columns : [] })} required />
      {columnable && (
        <ColumnsInput connectionId={connectionId} schema={s(op.schema)} table={s(op.object)} label="Solo estas columnas (opcional)" description="Permiso por columna; vacío = toda la tabla" value={(op.columns as string[]) ?? []} onChange={(v) => set({ columns: v })} />
      )}
      {op.type === "GRANT" && <Switch label="WITH GRANT OPTION (puede re-otorgar)" checked={!!op.with_grant_option} onChange={(e) => set({ with_grant_option: e.currentTarget.checked })} mt={columnable ? 28 : 0} />}
    </SimpleGrid>
  );
}

function SynonymEditor({ op, onChange, connectionId }: EditorProps) {
  const set = (patch: Record<string, unknown>) => onChange({ ...op, ...patch });
  const create = op.type === "CREATE_SYNONYM";
  return (
    <SimpleGrid cols={{ base: 1, sm: 2 }}>
      <SchemaInput connectionId={connectionId} label="Dueño del sinónimo" description="Normalmente el usuario lector; PUBLIC para sinónimo público" extra={["PUBLIC"]} value={s(op.schema)} onChange={(v) => set({ schema: v })} />
      {create ? (
        <TextInput label="Nombre del sinónimo" value={s(op.synonym)} onChange={(e) => set({ synonym: up(e.currentTarget.value) })} required />
      ) : (
        <ObjectInput connectionId={connectionId} schema={s(op.schema)} label="Sinónimo" types={["SYNONYM"]} value={s(op.synonym)} onChange={(v) => set({ synonym: v })} />
      )}
      {create && (
        <>
          <SchemaInput connectionId={connectionId} label="Esquema del objeto destino" value={s(op.target_schema)} onChange={(v) => set({ target_schema: v, synonym: s(op.synonym) || s(op.target_object) })} />
          <ObjectInput connectionId={connectionId} schema={s(op.target_schema)} label="Objeto destino" value={s(op.target_object)} onChange={(v) => set({ target_object: v, synonym: s(op.synonym) || v })} />
          <Switch label="Reemplazar si existe (OR REPLACE)" checked={!!op.replace} onChange={(e) => set({ replace: e.currentTarget.checked })} />
        </>
      )}
    </SimpleGrid>
  );
}

function CreateUserEditor({ op, onChange, meta }: EditorProps) {
  const set = (patch: Record<string, unknown>) => onChange({ ...op, ...patch });
  return (
    <Stack gap="sm">
      <SimpleGrid cols={{ base: 1, sm: 2 }}>
        <TextInput label="Nombre del usuario" value={s(op.schema)} onChange={(e) => set({ schema: up(e.currentTarget.value) })} required />
        <div>
          <Text size="sm" fw={500} mb={4}>Tipo</Text>
          <SegmentedControl data={[{ value: "READER", label: "Lector (solo consulta)" }, { value: "OWNER", label: "Owner (crea objetos)" }]} value={s(op.kind) || "READER"}
            onChange={(v) => set(v === "OWNER"
              ? { kind: v, quota: op.quota ?? "UNLIMITED", default_tablespace: op.default_tablespace ?? "USERS", system_privileges: ["CREATE SESSION", "CREATE TABLE", "CREATE VIEW", "CREATE SEQUENCE", "CREATE PROCEDURE", "CREATE TRIGGER", "CREATE SYNONYM", "CREATE MATERIALIZED VIEW"] }
              : { kind: v, quota: null, system_privileges: ["CREATE SESSION", "CREATE SYNONYM"] })} />
        </div>
        <TextInput label="Tablespace por defecto (opcional)" value={s(op.default_tablespace)} onChange={(e) => set({ default_tablespace: up(e.currentTarget.value) || null })} />
        <TextInput label="Cuota (opcional)" description="UNLIMITED o p. ej. 500M, 2G" value={s(op.quota)} onChange={(e) => set({ quota: up(e.currentTarget.value) || null })} />
        <MultiSelect label="Privilegios de sistema" data={meta.system_privileges} value={(op.system_privileges as string[]) ?? []} onChange={(v) => set({ system_privileges: v })} />
        <TagsInput label="Roles" value={(op.roles as string[]) ?? []} onChange={(v) => set({ roles: v.map(up).filter(Boolean) })} />
      </SimpleGrid>
      <Text size="xs" c="dimmed">La contraseña la genera DBA Manager al ejecutar y se muestra una sola vez al DBA que ejecuta; no se guarda.</Text>
    </Stack>
  );
}

// ------------------------------------------------------------------ código
const CODE_KINDS = ["PROCEDURE", "FUNCTION", "PACKAGE", "PACKAGE BODY", "TRIGGER", "VIEW", "MATERIALIZED VIEW", "TYPE"];

function codeTemplate(kind: string, schema: string, name: string): string {
  const q = `${schema || "ESQUEMA"}.${name || "NOMBRE"}`;
  switch (kind) {
    case "VIEW": return `CREATE OR REPLACE VIEW ${q} AS\nSELECT ...\n  FROM ...`;
    case "MATERIALIZED VIEW": return `CREATE MATERIALIZED VIEW ${q}\n  BUILD IMMEDIATE\n  REFRESH COMPLETE ON DEMAND\nAS\nSELECT ...\n  FROM ...`;
    case "TRIGGER": return `CREATE OR REPLACE TRIGGER ${q}\n  BEFORE INSERT OR UPDATE ON ${schema || "ESQUEMA"}.TABLA\n  FOR EACH ROW\nBEGIN\n  :NEW.COLUMNA := ...;\nEND;`;
    case "FUNCTION": return `CREATE OR REPLACE FUNCTION ${q} (p_valor IN NUMBER) RETURN NUMBER AS\nBEGIN\n  RETURN p_valor;\nEND;`;
    case "PACKAGE": return `CREATE OR REPLACE PACKAGE ${q} AS\n  PROCEDURE ejemplo;\nEND;`;
    case "PACKAGE BODY": return `CREATE OR REPLACE PACKAGE BODY ${q} AS\n  PROCEDURE ejemplo IS\n  BEGIN\n    NULL;\n  END;\nEND;`;
    case "TYPE": return `CREATE OR REPLACE TYPE ${q} AS OBJECT (\n  campo VARCHAR2(100)\n);`;
    default: return `CREATE OR REPLACE PROCEDURE ${q} AS\nBEGIN\n  NULL;\nEND;`;
  }
}

function CodeEditor({ op, onChange, connectionId }: EditorProps) {
  const set = (patch: Record<string, unknown>) => onChange({ ...op, ...patch });
  const kind = s(op.kind) || "PROCEDURE";
  const loadCurrent = () => {
    if (!connectionId || !op.schema || !op.name) return;
    fetchDdl(connectionId, s(op.schema), kind, s(op.name))
      .then((r) => set({ source: r.ddl.trim().replace(/\n\s*\/\s*$/, "").trim() }))
      .catch(notifyError);
  };
  const template = codeTemplate(kind, s(op.schema), s(op.name));
  return (
    <Stack gap="sm">
      <SimpleGrid cols={{ base: 1, sm: 3 }}>
        <SchemaInput connectionId={connectionId} label="Esquema" value={s(op.schema)} onChange={(v) => set({ schema: v })} />
        <Select label="Tipo" data={CODE_KINDS} value={kind} onChange={(v) => set({ kind: v })} allowDeselect={false} />
        <ObjectInput connectionId={connectionId} schema={s(op.schema)} label="Nombre" description={kind === "MATERIALIZED VIEW" ? "Una vista materializada no se reemplaza: debe no existir" : "Si ya existe se reemplaza"} value={s(op.name)} onChange={(v) => set({ name: v })} />
      </SimpleGrid>
      <Group justify="space-between">
        <Text size="sm" fw={500}>Código completo</Text>
        <Group gap="xs">
          <Button size="compact-xs" variant="subtle" onClick={() => set({ source: template })}>Usar plantilla</Button>
          <Tooltip label="Trae el código actual desde la base para editarlo">
            <Button size="compact-xs" variant="light" leftSection={<IconDownload size={14} />} onClick={loadCurrent}>Cargar código actual</Button>
          </Tooltip>
        </Group>
      </Group>
      <Textarea value={s(op.source)} onChange={(e) => set({ source: e.currentTarget.value })} autosize minRows={8} maxRows={30}
        styles={{ input: { fontFamily: "monospace", fontSize: 12.5 } }} placeholder={template} />
    </Stack>
  );
}

function DropCodeEditor({ op, onChange, connectionId }: EditorProps) {
  const set = (patch: Record<string, unknown>) => onChange({ ...op, ...patch });
  return (
    <SimpleGrid cols={{ base: 1, sm: 3 }}>
      <SchemaInput connectionId={connectionId} label="Esquema" value={s(op.schema)} onChange={(v) => set({ schema: v })} />
      <Select label="Tipo" data={[...CODE_KINDS, "SEQUENCE"]} value={s(op.kind) || null} onChange={(v) => set({ kind: v })} />
      <ObjectInput connectionId={connectionId} schema={s(op.schema)} label="Nombre" value={s(op.name)} onChange={(v) => set({ name: v })} />
    </SimpleGrid>
  );
}

function CustomSqlEditor({ op, onChange, connectionId }: EditorProps) {
  const set = (patch: Record<string, unknown>) => onChange({ ...op, ...patch });
  return (
    <Stack gap="sm">
      <SimpleGrid cols={{ base: 1, sm: 2 }}>
        <SchemaInput connectionId={connectionId} label="Esquema principal afectado" value={s(op.schema)} onChange={(v) => set({ schema: v })} />
        <TextInput label="Qué hace este SQL" value={s(op.description)} onChange={(e) => set({ description: e.currentTarget.value })} required />
      </SimpleGrid>
      <Textarea label="SQL" description="Sentencias separadas por ';' y bloques PL/SQL terminados con una línea '/'" value={s(op.sql)} onChange={(e) => set({ sql: e.currentTarget.value })}
        autosize minRows={6} maxRows={30} styles={{ input: { fontFamily: "monospace", fontSize: 12.5 } }} />
    </Stack>
  );
}

export function OperationEditor(props: EditorProps) {
  switch (props.op.type) {
    case "CREATE_TABLE": return <CreateTableEditor {...props} />;
    case "ADD_COLUMN":
    case "MODIFY_COLUMN": return <TableColumnEditor {...props} />;
    case "DROP_COLUMN": return <DropColumnEditor {...props} />;
    case "DROP_TABLE": return <DropTableEditor {...props} />;
    case "ALTER_TABLE_OPTIONS": return <AlterTableOptionsEditor {...props} />;
    case "RENAME": return <RenameEditor {...props} />;
    case "SET_COMMENT": return <SetCommentEditor {...props} />;
    case "ADD_CONSTRAINT": return <AddConstraintEditor {...props} />;
    case "DROP_CONSTRAINT":
    case "CONSTRAINT_STATE": return <ConstraintPickEditor {...props} />;
    case "CREATE_INDEX": return <CreateIndexEditor {...props} />;
    case "DROP_INDEX":
    case "ALTER_INDEX": return <IndexPickEditor {...props} />;
    case "ADD_PARTITION":
    case "DROP_PARTITION": return <PartitionOpEditor {...props} />;
    case "CREATE_SEQUENCE":
    case "ALTER_SEQUENCE": return <SequenceEditor {...props} />;
    case "CREATE_MV_LOG":
    case "DROP_MV_LOG": return <MvLogEditor {...props} />;
    case "GRANT":
    case "REVOKE": return <GrantEditor {...props} />;
    case "CREATE_SYNONYM":
    case "DROP_SYNONYM": return <SynonymEditor {...props} />;
    case "CREATE_USER": return <CreateUserEditor {...props} />;
    case "CREATE_OR_REPLACE_CODE": return <CodeEditor {...props} />;
    case "DROP_CODE": return <DropCodeEditor {...props} />;
    case "CUSTOM_SQL": return <CustomSqlEditor {...props} />;
    default: return <Text c="red">Operación desconocida: {props.op.type}</Text>;
  }
}

export function emptyOperation(type: string): Operation {
  switch (type) {
    case "CREATE_TABLE": return { type, schema: "", table: "", comment: "", columns: [{ name: "ID", data_type: "NUMBER", precision: 10, nullable: false, identity: true }], primary_key: ["ID"], constraints: [] };
    case "ADD_COLUMN": return { type, schema: "", table: "", column: { name: "", data_type: "VARCHAR2", length: 100, nullable: true } };
    case "MODIFY_COLUMN": return { type, schema: "", table: "", column: { name: "", data_type: "VARCHAR2", nullable: true }, previous: null };
    case "DROP_COLUMN": return { type, schema: "", table: "", column_name: "" };
    case "DROP_TABLE": return { type, schema: "", table: "", cascade_constraints: false, purge: false };
    case "ALTER_TABLE_OPTIONS": return { type, schema: "", table: "" };
    case "RENAME": return { type, schema: "", target: "TABLE", table: null, old_name: "", new_name: "" };
    case "SET_COMMENT": return { type, schema: "", table: "", column: null, comment: "" };
    case "ADD_CONSTRAINT": return { type, schema: "", table: "", constraint: { kind: "UNIQUE", columns: [] } };
    case "DROP_CONSTRAINT": return { type, schema: "", table: "", constraint_name: "", cascade: false, index: null };
    case "CONSTRAINT_STATE": return { type, schema: "", table: "", constraint_name: "", action: "DISABLE", validate_existing: true };
    case "CREATE_INDEX": return { type, schema: "", table: "", index_name: "", index_type: "NORMAL", columns: [{ expression: "", descending: false }] };
    case "DROP_INDEX": return { type, schema: "", table: "", index_name: "", online: false };
    case "ALTER_INDEX": return { type, schema: "", table: "", index_name: "", action: "REBUILD", online: true };
    case "ADD_PARTITION": return { type, schema: "", table: "", method: "RANGE", partition: { name: "", values: "" } };
    case "DROP_PARTITION": return { type, schema: "", table: "", partition_name: "", update_indexes: true };
    case "CREATE_SEQUENCE": return { type, schema: "", name: "", start_with: 1, increment_by: 1 };
    case "ALTER_SEQUENCE": return { type, schema: "", name: "" };
    case "CREATE_MV_LOG": return { type, schema: "", table: "", with_primary_key: true, with_rowid: false, with_sequence: false, columns: [], including_new_values: false };
    case "DROP_MV_LOG": return { type, schema: "", table: "" };
    case "GRANT": return { type, schema: "", object: "", grantee: "", privileges: ["SELECT"], columns: [], with_grant_option: false };
    case "REVOKE": return { type, schema: "", object: "", grantee: "", privileges: [] };
    case "CREATE_SYNONYM": return { type, schema: "", synonym: "", target_schema: "", target_object: "", replace: true };
    case "DROP_SYNONYM": return { type, schema: "", synonym: "" };
    case "CREATE_USER": return { type, schema: "", kind: "READER", system_privileges: ["CREATE SESSION", "CREATE SYNONYM"], roles: [] };
    case "CREATE_OR_REPLACE_CODE": return { type, schema: "", kind: "PROCEDURE", name: "", source: "" };
    case "DROP_CODE": return { type, schema: "", kind: "PROCEDURE", name: "" };
    case "CUSTOM_SQL": return { type, schema: "", description: "", sql: "" };
    default: return { type };
  }
}

/** Plantillas de casos frecuentes (varias operaciones relacionadas). */
export const TEMPLATES: { key: string; label: string; description: string; build: () => Operation[] }[] = [
  {
    key: "table_reader",
    label: "Tabla nueva + acceso para usuario lector",
    description: "Crea la tabla en el owner, da SELECT al lector y le crea el sinónimo",
    build: () => [emptyOperation("CREATE_TABLE"), { ...emptyOperation("GRANT"), privileges: ["SELECT"] }, emptyOperation("CREATE_SYNONYM")],
  },
  {
    key: "reader_user",
    label: "Usuario lector nuevo con acceso a una tabla",
    description: "Crea el usuario lector, le da SELECT y el sinónimo",
    build: () => [emptyOperation("CREATE_USER"), emptyOperation("GRANT"), emptyOperation("CREATE_SYNONYM")],
  },
  {
    key: "access_existing",
    label: "Acceso a tabla existente",
    description: "GRANT sobre un objeto existente + sinónimo en el usuario que lo usará",
    build: () => [emptyOperation("GRANT"), emptyOperation("CREATE_SYNONYM")],
  },
  {
    key: "table_seq_trigger",
    label: "Tabla con secuencia y trigger",
    description: "Tabla, secuencia y trigger BEFORE INSERT",
    build: () => [emptyOperation("CREATE_TABLE"), emptyOperation("CREATE_SEQUENCE"), { ...emptyOperation("CREATE_OR_REPLACE_CODE"), kind: "TRIGGER" }],
  },
];
