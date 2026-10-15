import {
  ActionIcon, Alert, Badge, Button, Card, Grid, Group, Loader, Menu, NavLink, ScrollArea, Select, Stack, Table,
  Tabs, Text, TextInput, Title, Tooltip,
} from "@mantine/core";
import { useQuery } from "@tanstack/react-query";
import {
  IconArrowRight, IconColumns, IconDots, IconEye, IconKey, IconLock, IconPlus, IconSearch, IconShieldLock,
  IconTable, IconTrash, IconUser, IconUserPlus,
} from "@tabler/icons-react";
import { useMemo, useState } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
import {
  api, ColumnInfo, DbObject, fetchDdl, Operation, SearchHit, TableInfo, useConnections, useObjects, useSchemas,
  useTable, useUserInfo,
} from "../api";
import { catalogToCol } from "../components/OperationEditors";
import { EnvBadge, fmtDate, notifyError, SqlBlock } from "../ui";

const TYPE_ICON: Record<string, React.ReactNode> = {
  TABLE: <IconTable size={15} />,
  VIEW: <IconEye size={15} />,
  SYNONYM: <IconArrowRight size={15} />,
};
const TYPE_ORDER = ["TABLE", "VIEW", "MATERIALIZED VIEW", "SYNONYM", "SEQUENCE", "PROCEDURE", "FUNCTION", "PACKAGE", "TRIGGER", "TYPE"];
const TYPE_LABEL: Record<string, string> = {
  TABLE: "Tablas", VIEW: "Vistas", "MATERIALIZED VIEW": "Vistas materializadas", SYNONYM: "Sinónimos",
  SEQUENCE: "Secuencias", PROCEDURE: "Procedimientos", FUNCTION: "Funciones", PACKAGE: "Paquetes",
  TRIGGER: "Triggers", TYPE: "Tipos",
};

/** Navega al editor de solicitudes con operaciones precargadas. */
function useRequestDraft() {
  const nav = useNavigate();
  return (connectionId: number, title: string, operations: Operation[]) =>
    nav("/requests/new", { state: { connectionId, title, operations } });
}

export default function ExplorerPage() {
  const [params, setParams] = useSearchParams();
  const { data: connections } = useConnections();
  const connId = Number(params.get("conn")) || connections?.[0]?.id || null;
  const schema = params.get("schema");
  const object = params.get("obj");
  const objectType = params.get("type");
  const [schemaFilter, setSchemaFilter] = useState("");
  const [objectFilter, setObjectFilter] = useState("");
  const [searchText, setSearchText] = useState("");
  const [search, setSearch] = useState<string | null>(null);

  const schemas = useSchemas(connId);
  const objects = useObjects(connId, schema);
  const conn = connections?.find((c) => c.id === connId);

  const set = (patch: Record<string, string | null>) => {
    const next = new URLSearchParams(params);
    Object.entries(patch).forEach(([k, v]) => (v ? next.set(k, v) : next.delete(k)));
    setParams(next);
  };

  const grouped = useMemo(() => {
    const g: Record<string, DbObject[]> = {};
    (objects.data ?? [])
      .filter((o) => o.name.includes(objectFilter.toUpperCase()))
      .forEach((o) => (g[o.type] ??= []).push(o));
    return TYPE_ORDER.filter((t) => g[t]).map((t) => [t, g[t]] as const);
  }, [objects.data, objectFilter]);

  if (connections && connections.length === 0) {
    return <Alert color="yellow">No hay conexiones configuradas. Un administrador debe darlas de alta en Administración.</Alert>;
  }

  return (
    <Stack gap="sm">
      <Group justify="space-between" wrap="wrap">
        <Group>
          <Select
            w={260}
            data={(connections ?? []).map((c) => ({ value: String(c.id), label: `${c.name} (${c.environment})` }))}
            value={connId ? String(connId) : null}
            onChange={(v) => set({ conn: v, schema: null, obj: null, type: null })}
            allowDeselect={false}
            leftSection={<IconShieldLock size={16} />}
          />
          {conn && <EnvBadge env={conn.environment} />}
          {conn && <Text size="xs" c="dimmed">{conn.host}/{conn.service_name}{conn.server_version ? ` · Oracle ${conn.server_version}` : ""}</Text>}
        </Group>
        <form
          onSubmit={(e) => {
            e.preventDefault();
            if (searchText.trim().length >= 2) setSearch(searchText.trim());
          }}
        >
          <TextInput
            w={320}
            placeholder="Buscar tabla, columna o comentario…"
            leftSection={<IconSearch size={16} />}
            value={searchText}
            onChange={(e) => setSearchText(e.currentTarget.value)}
          />
        </form>
      </Group>
      <Alert variant="light" color="blue" icon={<IconLock size={18} />} py={6}>
        Solo se muestra estructura (diccionario de datos). DBA Manager nunca consulta ni muestra datos de las tablas.
      </Alert>

      {search && connId && (
        <SearchResults
          connectionId={connId}
          text={search}
          onClose={() => setSearch(null)}
          onPick={(h) => {
            set({ schema: h.schema, obj: h.object_name, type: h.object_type });
            setSearch(null);
          }}
        />
      )}

      <Grid gutter="sm">
        <Grid.Col span={{ base: 12, md: 3, lg: 2.2 }}>
          <Card withBorder p={0}>
            <TextInput
              p="xs"
              size="xs"
              placeholder="Filtrar esquemas"
              value={schemaFilter}
              onChange={(e) => setSchemaFilter(e.currentTarget.value)}
            />
            <ScrollArea h="calc(100vh - 260px)">
              {schemas.isLoading && <Loader m="md" size="sm" />}
              {schemas.error && <Text c="red" p="sm" size="sm">{(schemas.error as Error).message}</Text>}
              {schemas.data
                ?.filter((s) => s.name.includes(schemaFilter.toUpperCase()))
                .map((s) => (
                  <NavLink
                    key={s.name}
                    active={s.name === schema}
                    label={s.name}
                    description={s.kind === "READER" ? `lector · ${s.received_grants} permisos` : `${s.tables} tablas · ${s.objects} objetos`}
                    leftSection={s.kind === "READER" ? <IconUser size={16} /> : <IconTable size={16} />}
                    rightSection={s.account_status !== "OPEN" ? <Tooltip label={s.account_status}><IconLock size={14} /></Tooltip> : null}
                    onClick={() => set({ schema: s.name, obj: null, type: null })}
                  />
                ))}
            </ScrollArea>
          </Card>
        </Grid.Col>

        <Grid.Col span={{ base: 12, md: 3, lg: 2.3 }}>
          <Card withBorder p={0}>
            {!schema ? (
              <Text p="md" c="dimmed" size="sm">Selecciona un esquema</Text>
            ) : (
              <>
                <TextInput
                  p="xs"
                  size="xs"
                  placeholder={`Filtrar objetos de ${schema}`}
                  value={objectFilter}
                  onChange={(e) => setObjectFilter(e.currentTarget.value)}
                />
                <ScrollArea h="calc(100vh - 260px)">
                  <NavLink
                    label={<Text fw={600} size="sm">Usuario {schema}</Text>}
                    description="Permisos, roles y sinónimos"
                    leftSection={<IconUser size={16} />}
                    active={!object}
                    onClick={() => set({ obj: null, type: null })}
                  />
                  {objects.isLoading && <Loader m="md" size="sm" />}
                  {grouped.map(([type, list]) => (
                    <NavLink key={type} label={`${TYPE_LABEL[type] ?? type} (${list.length})`} defaultOpened={type === "TABLE" || list.length < 15} childrenOffset={8}>
                      {list.map((o) => (
                        <NavLink
                          key={o.type + o.name}
                          label={o.name}
                          description={o.type === "SYNONYM" ? `→ ${o.target_schema}.${o.target_object}` : o.comments ?? undefined}
                          leftSection={TYPE_ICON[o.type]}
                          rightSection={o.status === "INVALID" ? <Badge size="xs" color="red">inválido</Badge> : null}
                          active={o.name === object && o.type === objectType}
                          onClick={() => set({ obj: o.name, type: o.type })}
                        />
                      ))}
                    </NavLink>
                  ))}
                </ScrollArea>
              </>
            )}
          </Card>
        </Grid.Col>

        <Grid.Col span={{ base: 12, md: 6, lg: 7.5 }}>
          {connId && schema && !object && <SchemaUserDetail connectionId={connId} schema={schema} />}
          {connId && schema && object && objectType && ["TABLE", "VIEW", "MATERIALIZED VIEW"].includes(objectType) && (
            <TableDetail connectionId={connId} schema={schema} table={object} onNavigate={(s, o, t) => set({ schema: s, obj: o, type: t })} />
          )}
          {connId && schema && object && objectType === "SYNONYM" && (
            <SynonymDetail
              connectionId={connId}
              schema={schema}
              name={object}
              target={objects.data?.find((o) => o.name === object && o.type === "SYNONYM")}
              onNavigate={(s, o) => set({ schema: s, obj: o, type: "TABLE" })}
            />
          )}
          {connId && schema && object && objectType && !["TABLE", "VIEW", "MATERIALIZED VIEW", "SYNONYM"].includes(objectType) && (
            <CodeDetail connectionId={connId} schema={schema} name={object} type={objectType} />
          )}
        </Grid.Col>
      </Grid>
    </Stack>
  );
}

function SearchResults({ connectionId, text, onClose, onPick }: { connectionId: number; text: string; onClose: () => void; onPick: (h: SearchHit) => void }) {
  const { data, isLoading, error } = useQuery({
    queryKey: ["search", connectionId, text],
    queryFn: () => api.get<SearchHit[]>(`/api/explorer/${connectionId}/search?q=${encodeURIComponent(text)}`),
  });
  return (
    <Card withBorder>
      <Group justify="space-between" mb="xs">
        <Text fw={600}>Resultados para “{text}”</Text>
        <Button size="xs" variant="subtle" onClick={onClose}>Cerrar</Button>
      </Group>
      {isLoading && <Loader size="sm" />}
      {error && <Text c="red">{(error as Error).message}</Text>}
      {data && data.length === 0 && <Text c="dimmed" size="sm">Sin coincidencias</Text>}
      {data && data.length > 0 && (
        <ScrollArea.Autosize mah={260}>
          <Table highlightOnHover striped>
            <Table.Tbody>
              {data.map((h, i) => (
                <Table.Tr key={i} style={{ cursor: "pointer" }} onClick={() => onPick(h)}>
                  <Table.Td><Badge size="xs" variant="light">{h.match_on === "COLUMN" ? "columna" : h.object_type.toLowerCase()}</Badge></Table.Td>
                  <Table.Td><Text size="sm" ff="monospace">{h.schema}.{h.object_name}{h.column_name ? `.${h.column_name}` : ""}</Text></Table.Td>
                  <Table.Td><Text size="xs" c="dimmed">{h.comments}</Text></Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </ScrollArea.Autosize>
      )}
    </Card>
  );
}

const CONS_TYPE: Record<string, { label: string; color: string }> = {
  P: { label: "PK", color: "yellow" }, U: { label: "Única", color: "blue" }, R: { label: "FK", color: "grape" }, C: { label: "Check", color: "gray" },
};

function TableDetail({ connectionId, schema, table, onNavigate }: { connectionId: number; schema: string; table: string; onNavigate: (s: string, o: string, t: string) => void }) {
  const { data: t, isLoading, error } = useTable(connectionId, schema, table);
  const draft = useRequestDraft();
  const [ddl, setDdl] = useState<string | null>(null);

  if (isLoading) return <Loader />;
  if (error) return <Alert color="red">{(error as Error).message}</Alert>;
  if (!t) return null;
  const isTable = t.type === "TABLE";
  const req = (title: string, ...ops: Operation[]) => draft(connectionId, title, ops);
  const base = { schema, table };
  const readOnly = t.read_only === "YES";

  const requestAccess = () =>
    req(`Acceso de lectura a ${schema}.${table}`,
      { type: "GRANT", schema, object: table, grantee: "", privileges: ["SELECT"], columns: [], with_grant_option: false },
      { type: "CREATE_SYNONYM", schema: "", synonym: table, target_schema: schema, target_object: table, replace: true });

  const props = [
    t.temporary === "Y" && `temporal (${t.duration === "SYS$SESSION" ? "se vacía al cerrar sesión" : "se vacía al COMMIT"})`,
    t.partitioning && `particionada ${t.partitioning.partitioning_type} (${t.partitioning.columns})${t.partitioning.interval ? " con INTERVAL" : ""}`,
    t.compression === "ENABLED" && `compresión ${t.compress_for ?? ""}`,
    t.logging === "NO" && "NOLOGGING",
    readOnly && "solo lectura",
    t.tablespace_name && `tablespace ${t.tablespace_name}`,
    t.mv_log && "con log de vista materializada",
  ].filter(Boolean) as string[];

  return (
    <Card withBorder>
      <Group justify="space-between" align="flex-start">
        <div>
          <Group gap="xs">
            <Title order={4} ff="monospace">{schema}.{table}</Title>
            <Badge variant="light">{t.type}</Badge>
            {t.status === "INVALID" && <Badge color="red">inválido</Badge>}
            {readOnly && <Badge color="orange">READ ONLY</Badge>}
          </Group>
          {t.comments && <Text size="sm" c="dimmed">{t.comments}</Text>}
          <Text size="xs" c="dimmed">Creado {fmtDate(t.created)} · último DDL {fmtDate(t.last_ddl_time)}</Text>
          {props.length > 0 && <Group gap={4} mt={4}>{props.map((p) => <Badge key={p} size="xs" variant="outline">{p}</Badge>)}</Group>}
        </div>
        <Group gap="xs">
          <Button size="xs" leftSection={<IconUserPlus size={16} />} onClick={requestAccess}>Solicitar acceso</Button>
          {t.type === "VIEW" && (
            <Button size="xs" variant="default" onClick={() => fetchDdl(connectionId, schema, "VIEW", table)
              .then((r) => req(`Actualizar vista ${schema}.${table}`, { type: "CREATE_OR_REPLACE_CODE", schema, kind: "VIEW", name: table, source: r.ddl.split(/\n\s*\n/)[0].trim().replace(/;$/, "").replace(/^CREATE (FORCE )?(EDITIONABLE )?VIEW/i, "CREATE OR REPLACE VIEW") }))
              .catch(notifyError)}>Actualizar definición</Button>
          )}
          {!isTable && (
            <Button size="xs" color="red" variant="light" leftSection={<IconTrash size={16} />}
              onClick={() => req(`Eliminar ${t.type.toLowerCase()} ${schema}.${table}`, { type: "DROP_CODE", schema, kind: t.type, name: table })}>Eliminar</Button>
          )}
          {isTable && (
            <Menu position="bottom-end" width={260}>
              <Menu.Target>
                <Button size="xs" variant="default" rightSection={<IconDots size={14} />}>Solicitar cambio</Button>
              </Menu.Target>
              <Menu.Dropdown>
                <Menu.Label>Columnas</Menu.Label>
                <Menu.Item leftSection={<IconColumns size={16} />} onClick={() => req(`Agregar columna a ${schema}.${table}`, { type: "ADD_COLUMN", ...base, column: { name: "", data_type: "VARCHAR2", length: 100, nullable: true } })}>Agregar columna</Menu.Item>
                <Menu.Item onClick={() => req(`Comentario de ${schema}.${table}`, { type: "SET_COMMENT", ...base, column: null, comment: t.comments ?? "" })}>Comentar tabla/columna</Menu.Item>
                <Menu.Label>Restricciones e índices</Menu.Label>
                <Menu.Item leftSection={<IconKey size={16} />} onClick={() => req(`Restricción en ${schema}.${table}`, { type: "ADD_CONSTRAINT", ...base, constraint: { kind: t.constraints.some((c) => c.type === "P") ? "UNIQUE" : "PRIMARY_KEY", columns: [] } })}>Agregar restricción (PK, única, FK, check)</Menu.Item>
                <Menu.Item onClick={() => req(`Índice en ${schema}.${table}`, { type: "CREATE_INDEX", ...base, index_name: `IX_${table}_`.slice(0, 128), index_type: "NORMAL", columns: [{ expression: "", descending: false }], local: t.partitioned === "YES" })}>Crear índice</Menu.Item>
                <Menu.Label>Tabla</Menu.Label>
                {t.partitioning && !t.partitioning.interval && (
                  <Menu.Item onClick={() => req(`Partición nueva en ${schema}.${table}`, { type: "ADD_PARTITION", ...base, method: t.partitioning!.partitioning_type, partition: { name: "", values: "" } })}>Agregar partición</Menu.Item>
                )}
                <Menu.Item onClick={() => req(`Opciones de ${schema}.${table}`, { type: "ALTER_TABLE_OPTIONS", ...base })}>Logging / compresión / solo lectura</Menu.Item>
                <Menu.Item onClick={() => req(`Renombrar ${schema}.${table}`, { type: "RENAME", schema, target: "TABLE", table: null, old_name: table, new_name: "" })}>Renombrar tabla</Menu.Item>
                <Menu.Item onClick={() => req(`Trigger en ${schema}.${table}`, { type: "CREATE_OR_REPLACE_CODE", schema, kind: "TRIGGER", name: `TRG_${table}_`.slice(0, 128), source: `CREATE OR REPLACE TRIGGER ${schema}.TRG_${table}_BI\n  BEFORE INSERT ON ${schema}.${table}\n  FOR EACH ROW\nBEGIN\n  NULL;\nEND;` })}>Crear trigger</Menu.Item>
                {t.mv_log ? (
                  <Menu.Item color="red" onClick={() => req(`Eliminar log de vista materializada de ${schema}.${table}`, { type: "DROP_MV_LOG", ...base })}>Eliminar log de vista materializada</Menu.Item>
                ) : (
                  <Menu.Item onClick={() => req(`Log de vista materializada en ${schema}.${table}`, { type: "CREATE_MV_LOG", ...base, with_primary_key: t.constraints.some((c) => c.type === "P"), with_rowid: !t.constraints.some((c) => c.type === "P"), with_sequence: false, columns: [], including_new_values: false })}>Crear log de vista materializada</Menu.Item>
                )}
                <Menu.Divider />
                <Menu.Item color="red" leftSection={<IconTrash size={16} />} onClick={() => req(`Eliminar tabla ${schema}.${table}`, { type: "DROP_TABLE", ...base, cascade_constraints: false, purge: false })}>Eliminar tabla</Menu.Item>
              </Menu.Dropdown>
            </Menu>
          )}
        </Group>
      </Group>

      <Tabs defaultValue="columns" mt="md" keepMounted={false} onChange={(v) => {
        if (v === "ddl" && ddl === null) fetchDdl(connectionId, schema, t.type, table).then((r) => setDdl(r.ddl)).catch(notifyError);
      }}>
        <Tabs.List>
          <Tabs.Tab value="columns">Columnas ({t.columns.length})</Tabs.Tab>
          <Tabs.Tab value="constraints">Restricciones ({t.constraints.length})</Tabs.Tab>
          {t.type !== "VIEW" && <Tabs.Tab value="indexes">Índices ({t.indexes.length})</Tabs.Tab>}
          {t.partitioning && <Tabs.Tab value="partitions">Particiones ({t.partitions.length})</Tabs.Tab>}
          {t.triggers.length > 0 && <Tabs.Tab value="triggers">Triggers ({t.triggers.length})</Tabs.Tab>}
          <Tabs.Tab value="grants">Permisos ({t.grants.length})</Tabs.Tab>
          <Tabs.Tab value="synonyms">Sinónimos ({t.synonyms.length})</Tabs.Tab>
          {t.referenced_by.length > 0 && <Tabs.Tab value="refs">Referenciada por ({t.referenced_by.length})</Tabs.Tab>}
          <Tabs.Tab value="ddl">DDL</Tabs.Tab>
        </Tabs.List>

        <Tabs.Panel value="columns" pt="sm">
          <ColumnsTable t={t}
            onModify={(c) => req(`Modificar columna ${schema}.${table}.${c.name}`, { type: "MODIFY_COLUMN", ...base, column: catalogToCol(c), previous: catalogToCol(c) })}
            onRename={(c) => req(`Renombrar columna ${schema}.${table}.${c.name}`, { type: "RENAME", schema, target: "COLUMN", table, old_name: c.name, new_name: "" })}
            onComment={(c) => req(`Comentario de ${schema}.${table}.${c.name}`, { type: "SET_COMMENT", ...base, column: c.name, comment: c.comments ?? "" })}
            onDrop={(c) => req(`Eliminar columna ${schema}.${table}.${c.name}`, { type: "DROP_COLUMN", ...base, column_name: c.name })} />
        </Tabs.Panel>

        <Tabs.Panel value="constraints" pt="sm">
          <ScrollArea>
            <Table striped>
              <Table.Thead><Table.Tr><Table.Th>Nombre</Table.Th><Table.Th>Tipo</Table.Th><Table.Th>Columnas</Table.Th><Table.Th>Detalle</Table.Th><Table.Th>Estado</Table.Th>{isTable && <Table.Th w={40} />}</Table.Tr></Table.Thead>
              <Table.Tbody>
                {t.constraints.map((c) => (
                  <Table.Tr key={c.name} style={{ opacity: c.enabled ? 1 : 0.6 }}>
                    <Table.Td ff="monospace" fz="sm">{c.name}</Table.Td>
                    <Table.Td style={{ whiteSpace: "nowrap" }}><Badge variant="light" size="sm" color={CONS_TYPE[c.type]?.color}>{CONS_TYPE[c.type]?.label ?? c.type}</Badge></Table.Td>
                    <Table.Td ff="monospace" fz="sm">{c.columns}</Table.Td>
                    <Table.Td fz="sm">
                      {c.type === "R" && (
                        <Text size="sm" c="blue" style={{ cursor: "pointer" }} onClick={() => onNavigate(c.ref_owner!, c.ref_table!, "TABLE")}>
                          → {c.ref_owner}.{c.ref_table} ({c.ref_columns}) {c.delete_rule && c.delete_rule !== "NO ACTION" ? `ON DELETE ${c.delete_rule}` : ""}
                        </Text>
                      )}
                      {c.type === "C" && <Text size="sm" ff="monospace">{c.condition}</Text>}
                      {c.index_name && c.type !== "C" && c.type !== "R" && <Text size="xs" c="dimmed">índice {c.index_name}</Text>}
                    </Table.Td>
                    <Table.Td>
                      <Group gap={4}>
                        <Badge size="xs" color={c.enabled ? "green" : "red"} variant="light">{c.enabled ? "habilitada" : "deshabilitada"}</Badge>
                        {!c.validated && <Badge size="xs" color="orange" variant="light">NOVALIDATE</Badge>}
                        {c.deferrable && <Badge size="xs" variant="light">{c.initially_deferred ? "diferida" : "diferible"}</Badge>}
                      </Group>
                    </Table.Td>
                    {isTable && (
                      <Table.Td>
                        <Menu position="bottom-end">
                          <Menu.Target><ActionIcon variant="subtle" size="sm"><IconDots size={14} /></ActionIcon></Menu.Target>
                          <Menu.Dropdown>
                            {c.enabled ? (
                              <Menu.Item onClick={() => req(`Deshabilitar ${c.name}`, { type: "CONSTRAINT_STATE", ...base, constraint_name: c.name, action: "DISABLE", validate_existing: true })}>Deshabilitar</Menu.Item>
                            ) : (
                              <Menu.Item onClick={() => req(`Habilitar ${c.name}`, { type: "CONSTRAINT_STATE", ...base, constraint_name: c.name, action: "ENABLE", validate_existing: true })}>Habilitar</Menu.Item>
                            )}
                            <Menu.Item onClick={() => req(`Renombrar ${c.name}`, { type: "RENAME", schema, target: "CONSTRAINT", table, old_name: c.name, new_name: "" })}>Renombrar</Menu.Item>
                            <Menu.Item color="red" onClick={() => req(`Eliminar restricción ${c.name}`, { type: "DROP_CONSTRAINT", ...base, constraint_name: c.name, cascade: false, index: null })}>Eliminar</Menu.Item>
                          </Menu.Dropdown>
                        </Menu>
                      </Table.Td>
                    )}
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </Table>
          </ScrollArea>
        </Tabs.Panel>

        <Tabs.Panel value="indexes" pt="sm">
          <ScrollArea>
            <Table striped>
              <Table.Thead><Table.Tr><Table.Th>Índice</Table.Th><Table.Th>Columnas / expresiones</Table.Th><Table.Th>Tipo</Table.Th><Table.Th>Atributos</Table.Th>{isTable && <Table.Th w={40} />}</Table.Tr></Table.Thead>
              <Table.Tbody>
                {t.indexes.map((i) => (
                  <Table.Tr key={i.name} style={{ opacity: i.visibility === "INVISIBLE" ? 0.6 : 1 }}>
                    <Table.Td ff="monospace" fz="sm">{i.name}{i.constraint_name && <Text size="xs" c="dimmed">soporta {i.constraint_name}</Text>}</Table.Td>
                    <Table.Td ff="monospace" fz="sm">{i.columns}</Table.Td>
                    <Table.Td style={{ whiteSpace: "nowrap" }}><Badge size="sm" variant="light" color={i.kind === "BITMAP" ? "teal" : i.kind === "UNIQUE" ? "blue" : i.kind === "DOMAIN" ? "grape" : "gray"}>{i.kind === "NORMAL" ? "B-tree" : i.kind}</Badge></Table.Td>
                    <Table.Td>
                      <Group gap={4}>
                        {i.function_based && <Badge size="xs" variant="outline">por función</Badge>}
                        {i.reverse && <Badge size="xs" variant="outline">REVERSE</Badge>}
                        {i.locality && <Badge size="xs" variant="outline">{i.locality}</Badge>}
                        {i.compression && i.compression !== "DISABLED" && <Badge size="xs" variant="outline">compresión {i.compression === "ENABLED" ? i.prefix_length : i.compression}</Badge>}
                        {i.visibility === "INVISIBLE" && <Badge size="xs" color="orange" variant="light">invisible</Badge>}
                        {i.status === "UNUSABLE" && <Badge size="xs" color="red">UNUSABLE</Badge>}
                        {i.indextype && <Badge size="xs" variant="outline">{i.indextype}</Badge>}
                        {i.tablespace_name && <Text size="xs" c="dimmed">{i.tablespace_name}</Text>}
                      </Group>
                    </Table.Td>
                    {isTable && (
                      <Table.Td>
                        <Menu position="bottom-end">
                          <Menu.Target><ActionIcon variant="subtle" size="sm"><IconDots size={14} /></ActionIcon></Menu.Target>
                          <Menu.Dropdown>
                            <Menu.Item disabled={i.partitioned === "YES"} onClick={() => req(`Reconstruir ${i.name}`, { type: "ALTER_INDEX", ...base, index_name: i.name, action: "REBUILD", online: true })}>Reconstruir</Menu.Item>
                            <Menu.Item onClick={() => req(`${i.visibility === "INVISIBLE" ? "Hacer visible" : "Hacer invisible"} ${i.name}`, { type: "ALTER_INDEX", ...base, index_name: i.name, action: i.visibility === "INVISIBLE" ? "VISIBLE" : "INVISIBLE" })}>
                              {i.visibility === "INVISIBLE" ? "Hacer visible" : "Hacer invisible"}
                            </Menu.Item>
                            <Menu.Item onClick={() => req(`Renombrar ${i.name}`, { type: "RENAME", schema, target: "INDEX", table, old_name: i.name, new_name: "" })}>Renombrar</Menu.Item>
                            <Menu.Item color="red" disabled={!!i.constraint_name} onClick={() => req(`Eliminar índice ${i.name}`, { type: "DROP_INDEX", ...base, index_name: i.name, online: false })}>
                              {i.constraint_name ? "Eliminar (soporta una restricción)" : "Eliminar"}
                            </Menu.Item>
                          </Menu.Dropdown>
                        </Menu>
                      </Table.Td>
                    )}
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </Table>
          </ScrollArea>
        </Tabs.Panel>

        <Tabs.Panel value="partitions" pt="sm">
          {t.partitioning && (
            <Text size="sm" mb="xs">
              {t.partitioning.partitioning_type} por <Text span ff="monospace">{t.partitioning.columns}</Text>
              {t.partitioning.interval && <> · INTERVAL <Text span ff="monospace">{t.partitioning.interval}</Text> (las particiones se crean solas)</>}
              {t.partitioning.subpartitioning_type !== "NONE" && ` · subparticiones ${t.partitioning.subpartitioning_type}`}
            </Text>
          )}
          <Table striped>
            <Table.Thead><Table.Tr><Table.Th>#</Table.Th><Table.Th>Partición</Table.Th><Table.Th>Límite / valores</Table.Th><Table.Th>Tablespace</Table.Th><Table.Th w={40} /></Table.Tr></Table.Thead>
            <Table.Tbody>
              {t.partitions.map((p) => (
                <Table.Tr key={p.name}>
                  <Table.Td fz="xs" c="dimmed">{p.position}</Table.Td>
                  <Table.Td ff="monospace" fz="sm">{p.name}{p.interval === "YES" && <Badge size="xs" ml={4} variant="light">automática</Badge>}</Table.Td>
                  <Table.Td ff="monospace" fz="xs">{p.high_value}</Table.Td>
                  <Table.Td fz="sm">{p.tablespace_name}</Table.Td>
                  <Table.Td>
                    <Tooltip label="Solicitar eliminar partición (borra sus datos)">
                      <ActionIcon variant="subtle" color="red" onClick={() => req(`Eliminar partición ${p.name} de ${schema}.${table}`, { type: "DROP_PARTITION", ...base, partition_name: p.name, update_indexes: true })}><IconTrash size={14} /></ActionIcon>
                    </Tooltip>
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </Tabs.Panel>

        <Tabs.Panel value="triggers" pt="sm">
          <Table striped>
            <Table.Tbody>
              {t.triggers.map((tr) => (
                <Table.Tr key={tr.owner + tr.name}>
                  <Table.Td><Text size="sm" ff="monospace" c="blue" style={{ cursor: "pointer" }} onClick={() => onNavigate(tr.owner, tr.name, "TRIGGER")}>{tr.owner}.{tr.name}</Text></Table.Td>
                  <Table.Td fz="sm">{tr.trigger_type} {tr.triggering_event}</Table.Td>
                  <Table.Td><Badge size="xs" color={tr.status === "ENABLED" ? "green" : "red"} variant="light">{tr.status}</Badge></Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </Tabs.Panel>

        <Tabs.Panel value="grants" pt="sm">
          {t.grants.length === 0 && <Text c="dimmed" size="sm">Nadie más tiene permisos sobre este objeto.</Text>}
          <Table striped>
            <Table.Tbody>
              {t.grants.map((g) => (
                <Table.Tr key={g.grantee}>
                  <Table.Td>
                    <Text size="sm" ff="monospace" c="blue" style={{ cursor: "pointer" }} onClick={() => onNavigate(g.grantee, "", "")}>{g.grantee}</Text>
                  </Table.Td>
                  <Table.Td>
                    <Group gap={4}>
                      {g.privileges.map((p) => <Badge key={p} size="sm" variant={g.grantable.includes(p) ? "filled" : "light"}>{p}</Badge>)}
                      {g.column_privileges.map((p) => <Badge key={p} size="sm" variant="outline">{p}</Badge>)}
                    </Group>
                  </Table.Td>
                  <Table.Td>
                    <Tooltip label="Solicitar revocar">
                      <ActionIcon variant="subtle" color="red" onClick={() => req(`Revocar permisos de ${g.grantee} sobre ${schema}.${table}`,
                        { type: "REVOKE", schema, object: table, grantee: g.grantee, privileges: [...new Set([...g.privileges, ...g.column_privileges.map((p) => p.split("(")[0])])] })}><IconTrash size={16} /></ActionIcon>
                    </Tooltip>
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </Tabs.Panel>

        <Tabs.Panel value="synonyms" pt="sm">
          {t.synonyms.length === 0 && <Text c="dimmed" size="sm">No hay sinónimos que apunten a este objeto.</Text>}
          {t.synonyms.map((sy) => <Text key={sy.owner + sy.name} ff="monospace" size="sm">{sy.owner}.{sy.name}</Text>)}
        </Tabs.Panel>

        <Tabs.Panel value="refs" pt="sm">
          {t.referenced_by.map((r) => (
            <Text key={r.name} size="sm" ff="monospace" c="blue" style={{ cursor: "pointer" }} onClick={() => onNavigate(r.owner, r.table_name, "TABLE")}>
              {r.owner}.{r.table_name} ({r.columns}) — {r.name}
            </Text>
          ))}
        </Tabs.Panel>

        <Tabs.Panel value="ddl" pt="sm">{ddl === null ? <Loader size="sm" /> : <SqlBlock sql={ddl} maxHeight={600} />}</Tabs.Panel>
      </Tabs>
    </Card>
  );
}

function ColumnsTable({ t, onModify, onRename, onComment, onDrop }: { t: TableInfo; onModify: (c: ColumnInfo) => void; onRename: (c: ColumnInfo) => void; onComment: (c: ColumnInfo) => void; onDrop: (c: ColumnInfo) => void }) {
  const pk = new Set(t.constraints.filter((c) => c.type === "P").flatMap((c) => c.columns.split(", ")));
  const fk = new Set(t.constraints.filter((c) => c.type === "R").flatMap((c) => c.columns.split(", ")));
  const identityLabel = (c: ColumnInfo) => ({ ALWAYS: "identidad ALWAYS", BY_DEFAULT: "identidad", BY_DEFAULT_ON_NULL: "identidad ON NULL" })[c.identity_mode ?? "BY_DEFAULT"];
  return (
    <ScrollArea>
      <Table striped highlightOnHover>
        <Table.Thead>
          <Table.Tr>
            <Table.Th w={30}>#</Table.Th>
            <Table.Th>Columna</Table.Th>
            <Table.Th>Tipo</Table.Th>
            <Table.Th>Nulo</Table.Th>
            <Table.Th>Default / expresión</Table.Th>
            <Table.Th>Comentario</Table.Th>
            {t.type === "TABLE" && <Table.Th w={40} />}
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {t.columns.map((c) => (
            <Table.Tr key={c.name} style={{ opacity: c.invisible ? 0.7 : 1 }}>
              <Table.Td c="dimmed" fz="xs">{c.column_id ?? "—"}</Table.Td>
              <Table.Td>
                <Group gap={4}>
                  <Text ff="monospace" size="sm" fw={pk.has(c.name) ? 700 : 400}>{c.name}</Text>
                  {pk.has(c.name) && <Badge size="xs" color="yellow">PK</Badge>}
                  {fk.has(c.name) && <Badge size="xs" color="grape">FK</Badge>}
                  {c.identity && <Tooltip label={`Inicia ${c.identity_start ?? 1}, incremento ${c.identity_increment ?? 1}`}><Badge size="xs" color="cyan">{identityLabel(c)}</Badge></Tooltip>}
                  {c.virtual_expression && <Badge size="xs" color="teal">virtual</Badge>}
                  {c.invisible && <Badge size="xs" color="gray">invisible</Badge>}
                </Group>
              </Table.Td>
              <Table.Td ff="monospace" fz="sm">{c.type_display}</Table.Td>
              <Table.Td fz="sm">{c.nullable ? "Sí" : <Text span fw={600} size="sm">NOT NULL</Text>}</Table.Td>
              <Table.Td ff="monospace" fz="xs">
                {c.virtual_expression ? `AS (${c.virtual_expression})` : c.default_value ? `${c.default_on_null ? "ON NULL " : ""}${c.default_value}` : ""}
              </Table.Td>
              <Table.Td fz="sm" c="dimmed">{c.comments}</Table.Td>
              {t.type === "TABLE" && (
                <Table.Td>
                  <Menu position="bottom-end">
                    <Menu.Target><ActionIcon variant="subtle" size="sm"><IconDots size={14} /></ActionIcon></Menu.Target>
                    <Menu.Dropdown>
                      <Menu.Item onClick={() => onModify(c)}>Solicitar modificación</Menu.Item>
                      <Menu.Item onClick={() => onRename(c)}>Solicitar renombrar</Menu.Item>
                      <Menu.Item onClick={() => onComment(c)}>Solicitar comentario</Menu.Item>
                      <Menu.Item color="red" onClick={() => onDrop(c)}>Solicitar eliminación</Menu.Item>
                    </Menu.Dropdown>
                  </Menu>
                </Table.Td>
              )}
            </Table.Tr>
          ))}
        </Table.Tbody>
      </Table>
    </ScrollArea>
  );
}

function SchemaUserDetail({ connectionId, schema }: { connectionId: number; schema: string }) {
  const { data: u, isLoading, error } = useUserInfo(connectionId, schema);
  const draft = useRequestDraft();
  if (isLoading) return <Loader />;
  if (error) return <Alert color="red">{(error as Error).message}</Alert>;
  if (!u) return null;
  return (
    <Card withBorder>
      <Group justify="space-between" align="flex-start">
        <div>
          <Group gap="xs">
            <Title order={4} ff="monospace">{u.username}</Title>
            <Badge color={u.account_status === "OPEN" ? "green" : "gray"} variant="light">{u.account_status}</Badge>
          </Group>
          <Text size="xs" c="dimmed">
            Creado {fmtDate(u.created)} · tablespace {u.default_tablespace} · perfil {u.profile}
            {u.expiry_date ? ` · expira ${fmtDate(u.expiry_date)}` : ""}
          </Text>
        </div>
        <Group gap="xs">
          <Button size="xs" leftSection={<IconUserPlus size={16} />} onClick={() => draft(connectionId, `Permisos para ${schema}`, [
            { type: "GRANT", schema: "", object: "", grantee: schema, privileges: ["SELECT"], with_grant_option: false },
            { type: "CREATE_SYNONYM", schema, synonym: "", target_schema: "", target_object: "", replace: true },
          ])}>Solicitar permisos para este usuario</Button>
          <Button size="xs" variant="default" leftSection={<IconPlus size={16} />} onClick={() => draft(connectionId, `Nueva tabla en ${schema}`, [
            { type: "CREATE_TABLE", schema, table: "", comment: "", columns: [{ name: "ID", data_type: "NUMBER", precision: 10, nullable: false, identity: true }], primary_key: ["ID"] },
          ])}>Nueva tabla aquí</Button>
        </Group>
      </Group>

      <Grid mt="md">
        <Grid.Col span={{ base: 12, lg: 6 }}>
          <Text fw={600} size="sm" mb={4}>Privilegios de sistema y roles</Text>
          <Group gap={4}>
            {u.system_privileges.map((p) => <Badge key={p.privilege} variant="light">{p.privilege}</Badge>)}
            {u.roles.map((r) => <Badge key={r.role} variant="outline" color="grape">rol {r.role}</Badge>)}
            {u.system_privileges.length + u.roles.length === 0 && <Text size="sm" c="dimmed">Ninguno</Text>}
          </Group>
          {u.quotas.length > 0 && (
            <Text size="xs" c="dimmed" mt={6}>
              Cuotas: {u.quotas.map((q) => `${q.tablespace_name} ${q.max_bytes === -1 ? "ilimitada" : `${Math.round(q.max_bytes / 1048576)} MB`}`).join(", ")}
            </Text>
          )}
        </Grid.Col>
        <Grid.Col span={{ base: 12, lg: 6 }}>
          <Text fw={600} size="sm" mb={4}>Sinónimos propios ({u.synonyms.length})</Text>
          {u.synonyms.map((s) => <Text key={s.name} size="sm" ff="monospace">{s.name} → {s.target_schema}.{s.target_object}</Text>)}
        </Grid.Col>
      </Grid>

      <Text fw={600} size="sm" mt="md" mb={4}>Permisos recibidos sobre objetos de otros esquemas ({u.object_privileges.length})</Text>
      <Table striped>
        <Table.Tbody>
          {u.object_privileges.map((p) => (
            <Table.Tr key={p.owner + p.object_name}>
              <Table.Td ff="monospace" fz="sm">{p.owner}.{p.object_name}</Table.Td>
              <Table.Td fz="xs" c="dimmed">{p.object_type?.toLowerCase()}</Table.Td>
              <Table.Td><Group gap={4}>{p.privileges.split(", ").map((x) => <Badge key={x} size="sm" variant="light">{x}</Badge>)}</Group></Table.Td>
            </Table.Tr>
          ))}
        </Table.Tbody>
      </Table>
    </Card>
  );
}

function SynonymDetail({ connectionId, schema, name, target, onNavigate }: { connectionId: number; schema: string; name: string; target?: DbObject; onNavigate: (s: string, o: string) => void }) {
  const draft = useRequestDraft();
  return (
    <Card withBorder>
      <Group justify="space-between">
        <Title order={4} ff="monospace">{schema}.{name}</Title>
        <Button size="xs" color="red" variant="light" leftSection={<IconTrash size={16} />}
          onClick={() => draft(connectionId, `Eliminar sinónimo ${schema}.${name}`, [{ type: "DROP_SYNONYM", schema, synonym: name }])}>
          Solicitar eliminación
        </Button>
      </Group>
      {target && (
        <Text mt="sm">
          Sinónimo de{" "}
          <Text span ff="monospace" c="blue" style={{ cursor: "pointer" }} onClick={() => onNavigate(target.target_schema!, target.target_object!)}>
            {target.target_schema}.{target.target_object}
          </Text>
        </Text>
      )}
    </Card>
  );
}

/** Divide el DDL de un paquete (spec + body) en dos operaciones. */
function codeOps(schema: string, name: string, type: string, ddl: string): Operation[] {
  const clean = (s: string) => s.trim().replace(/\n\s*\/\s*$/, "").trim();
  if (type === "PACKAGE") {
    const idx = ddl.search(/CREATE\s+OR\s+REPLACE\s+(EDITIONABLE\s+|NONEDITIONABLE\s+)?PACKAGE\s+BODY/i);
    const ops: Operation[] = [{ type: "CREATE_OR_REPLACE_CODE", schema, kind: "PACKAGE", name, source: clean(idx > 0 ? ddl.slice(0, idx) : ddl) }];
    if (idx > 0) ops.push({ type: "CREATE_OR_REPLACE_CODE", schema, kind: "PACKAGE BODY", name, source: clean(ddl.slice(idx)) });
    return ops;
  }
  return [{ type: "CREATE_OR_REPLACE_CODE", schema, kind: type, name, source: clean(ddl) }];
}

function CodeDetail({ connectionId, schema, name, type }: { connectionId: number; schema: string; name: string; type: string }) {
  const draft = useRequestDraft();
  const { data, isLoading, error } = useQuery({
    queryKey: ["ddl", connectionId, schema, type, name],
    queryFn: () => fetchDdl(connectionId, schema, type, name),
  });
  const editable = ["PROCEDURE", "FUNCTION", "PACKAGE", "TRIGGER", "TYPE", "VIEW"].includes(type);
  return (
    <Card withBorder>
      <Group justify="space-between">
        <Group gap="xs">
          <Title order={4} ff="monospace">{schema}.{name}</Title>
          <Badge variant="light">{type}</Badge>
        </Group>
        <Group gap="xs">
          {type === "SEQUENCE" && (
            <Button size="xs" onClick={() => draft(connectionId, `Modificar secuencia ${schema}.${name}`, [{ type: "ALTER_SEQUENCE", schema, name }])}>Solicitar modificación</Button>
          )}
          {editable && data && (
            <Button size="xs" onClick={() => draft(connectionId, `Actualizar ${type.toLowerCase()} ${schema}.${name}`, codeOps(schema, name, type, data.ddl))}>
              Solicitar actualización
            </Button>
          )}
          <Button size="xs" color="red" variant="light" leftSection={<IconTrash size={16} />}
            onClick={() => draft(connectionId, `Eliminar ${type.toLowerCase()} ${schema}.${name}`, [{ type: "DROP_CODE", schema, kind: type, name }])}>
            Solicitar eliminación
          </Button>
        </Group>
      </Group>
      <div style={{ marginTop: 12 }}>
        {isLoading && <Loader size="sm" />}
        {error && <Alert color="red">{(error as Error).message}</Alert>}
        {data && <SqlBlock sql={data.ddl} maxHeight={640} />}
      </div>
    </Card>
  );
}
