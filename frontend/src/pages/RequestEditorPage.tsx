import {
  ActionIcon, Alert, Badge, Button, Card, Grid, Group, Loader, Menu, ScrollArea, Select, SimpleGrid, Stack, Text, Textarea,
  TextInput, Title, Tooltip,
} from "@mantine/core";
import { useDebouncedValue } from "@mantine/hooks";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { IconArrowDown, IconArrowUp, IconCopy, IconFileImport, IconPlus, IconSend, IconTemplate, IconTrash } from "@tabler/icons-react";
import { useEffect, useMemo, useRef, useState } from "react";
import { useLocation, useNavigate, useParams } from "react-router-dom";
import { api, ApiError, metaForVersion, Operation, Preview, RequestDetail, useConnections, useMeta, useProjects } from "../api";
import ImportSqlModal from "../components/ImportSqlModal";
import { emptyOperation, OperationEditor, TEMPLATES } from "../components/OperationEditors";
import { EnvBadge, notifyError, notifyOk, SqlBlock } from "../ui";

const OP_GROUPS = [
  { label: "Tablas y columnas", types: ["CREATE_TABLE", "ADD_COLUMN", "MODIFY_COLUMN", "DROP_COLUMN", "RENAME", "SET_COMMENT", "ALTER_TABLE_OPTIONS", "DROP_TABLE"] },
  { label: "Restricciones e índices", types: ["ADD_CONSTRAINT", "CONSTRAINT_STATE", "DROP_CONSTRAINT", "CREATE_INDEX", "ALTER_INDEX", "DROP_INDEX"] },
  { label: "Particiones", types: ["ADD_PARTITION", "DROP_PARTITION"] },
  { label: "Permisos, sinónimos y usuarios", types: ["GRANT", "REVOKE", "CREATE_SYNONYM", "DROP_SYNONYM", "CREATE_USER"] },
  { label: "Secuencias, vistas materializadas y código", types: ["CREATE_SEQUENCE", "ALTER_SEQUENCE", "CREATE_MV_LOG", "DROP_MV_LOG", "CREATE_OR_REPLACE_CODE", "DROP_CODE"] },
  { label: "Otro", types: ["CUSTOM_SQL"] },
];

type OpItem = { key: number; op: Operation; notes?: string[] };
let nextKey = 1;
const wrap = (ops: Operation[], notes: string[][] = []): OpItem[] => ops.map((op, i) => ({ key: nextKey++, op, notes: notes[i] }));

interface DraftState {
  connectionId?: number;
  title?: string;
  operations?: Operation[];
}

export default function RequestEditorPage() {
  const { id } = useParams();
  const editing = !!id;
  const nav = useNavigate();
  const qc = useQueryClient();
  const draft = (useLocation().state ?? {}) as DraftState;
  const { data: rawMeta } = useMeta();
  const { data: connections } = useConnections();
  const { data: projects } = useProjects();

  const [title, setTitle] = useState(draft.title ?? "");
  const [connectionId, setConnectionId] = useState<number | null>(draft.connectionId ?? null);
  const [projectId, setProjectId] = useState<number | null>(null);
  const [reason, setReason] = useState("");
  const [purpose, setPurpose] = useState("");
  const [ticketRef, setTicketRef] = useState("");
  const [ops, setOps] = useState<OpItem[]>(wrap(draft.operations ?? []));
  const [saving, setSaving] = useState(false);
  const [importOpen, setImportOpen] = useState(false);
  const loaded = useRef(false);

  const existing = useQuery({
    queryKey: ["request", id],
    queryFn: () => api.get<RequestDetail>(`/api/requests/${id}`),
    enabled: editing,
  });

  useEffect(() => {
    if (!connectionId && connections?.length) setConnectionId(connections[0].id);
  }, [connections, connectionId]);

  useEffect(() => {
    const r = existing.data;
    if (!r || loaded.current) return;
    loaded.current = true;
    setTitle(r.title);
    setConnectionId(r.connection.id);
    setProjectId(r.project?.id ?? null);
    setReason(r.reason);
    setPurpose(r.purpose ?? "");
    setTicketRef(r.ticket_ref ?? "");
    setOps(wrap(r.operations.map((o) => o.payload)));
  }, [existing.data]);

  // --- vista previa en vivo (validación + SQL + revisión contra el diccionario)
  const payload = useMemo(() => ({ connection_id: connectionId, operations: ops.map((o) => o.op), preflight: true }), [connectionId, ops]);
  const [debounced] = useDebouncedValue(payload, 900);
  const preview = useQuery({
    queryKey: ["preview", debounced],
    queryFn: () => api.post<Preview>("/api/requests/preview", debounced),
    enabled: !!debounced.connection_id && debounced.operations.length > 0,
    retry: false,
    placeholderData: (prev) => prev,
  });
  const previewError = preview.error as ApiError | null;

  const conn = connections?.find((c) => c.id === connectionId);
  const meta = useMemo(() => (rawMeta ? metaForVersion(rawMeta, conn?.server_version) : rawMeta), [rawMeta, conn?.server_version]);
  const opLabel = (t: string) => meta?.operations.find((o) => o.type === t)?.label ?? t;
  const destructive = (t: string) => meta?.operations.find((o) => o.type === t)?.destructive;

  const updateOp = (key: number, op: Operation) => setOps((cur) => cur.map((o) => (o.key === key ? { ...o, op } : o)));
  const move = (idx: number, dir: -1 | 1) =>
    setOps((cur) => {
      const next = [...cur];
      const [x] = next.splice(idx, 1);
      next.splice(idx + dir, 0, x);
      return next;
    });

  const save = async (submit: boolean) => {
    if (!connectionId) return;
    setSaving(true);
    const body = {
      title, connection_id: connectionId, project_id: projectId, reason, purpose: purpose || null,
      ticket_ref: ticketRef || null, operations: ops.map((o) => o.op), submit,
    };
    try {
      const r = editing ? await api.put<RequestDetail>(`/api/requests/${id}`, body) : await api.post<RequestDetail>("/api/requests", body);
      qc.invalidateQueries({ queryKey: ["requests"] });
      qc.invalidateQueries({ queryKey: ["request", String(r.id)] });
      notifyOk(submit ? `${r.code} enviada a aprobación` : `${r.code} guardada como borrador`);
      nav(`/requests/${r.id}`);
    } catch (e) {
      notifyError(e);
    } finally {
      setSaving(false);
    }
  };

  if (!meta || (editing && existing.isLoading)) return <Loader />;
  const errorsFor = (seq: number) => previewError?.details?.filter((d) => d.op_seq === seq) ?? [];
  const findingsFor = (seq: number) => preview.data?.findings.filter((f) => f.op_seq === seq) ?? [];
  const blocking = !!previewError || (preview.data?.findings.some((f) => f.level === "error") ?? false);
  const canSave = title.trim().length >= 5 && reason.trim().length >= 10 && ops.length > 0 && !!connectionId;

  return (
    <Grid gutter="md">
      <Grid.Col span={{ base: 12, xl: 7 }}>
        <Stack>
          <Title order={3}>{editing ? `Editar ${existing.data?.code}` : "Nueva solicitud"}</Title>

          <Card withBorder>
            <Stack gap="sm">
              <TextInput label="Título" placeholder="p. ej. Tabla de metas de venta para tablero de BI" value={title} onChange={(e) => setTitle(e.currentTarget.value)} required />
              <SimpleGrid cols={{ base: 1, sm: 3 }}>
                <Select label="Base de datos destino" data={(connections ?? []).map((c) => ({ value: String(c.id), label: `${c.name} (${c.environment})` }))}
                  value={connectionId ? String(connectionId) : null} onChange={(v) => setConnectionId(Number(v))} allowDeselect={false} required
                  rightSection={conn ? <EnvBadge env={conn.environment} /> : null} rightSectionWidth={60}
                  description={conn?.server_version ? `Oracle ${conn.server_version}` : undefined} />
                <Select label="Proyecto" placeholder="(opcional)" clearable searchable
                  data={(projects ?? []).filter((p) => p.active || p.id === projectId).map((p) => ({ value: String(p.id), label: `${p.code} — ${p.name}` }))}
                  value={projectId ? String(projectId) : null} onChange={(v) => setProjectId(v ? Number(v) : null)} />
                <TextInput label="Ticket / referencia" placeholder="(opcional) JIRA-123" value={ticketRef} onChange={(e) => setTicketRef(e.currentTarget.value)} />
              </SimpleGrid>
              <Textarea label="¿Por qué se necesita?" description="Motivo del cambio; queda en la bitácora para que cualquiera sepa por qué existe este objeto"
                value={reason} onChange={(e) => setReason(e.currentTarget.value)} autosize minRows={2} required />
              <Textarea label="¿Para qué / con qué fin?" description="Quién lo va a usar y cómo (reporte, proceso, integración…)" value={purpose} onChange={(e) => setPurpose(e.currentTarget.value)} autosize minRows={2} />
            </Stack>
          </Card>

          <Group justify="space-between">
            <Title order={4}>Operaciones ({ops.length})</Title>
            <Group gap="xs">
              <Button variant="default" size="xs" leftSection={<IconFileImport size={16} />} onClick={() => setImportOpen(true)} disabled={!connectionId}>
                Importar SQL
              </Button>
              <Menu position="bottom-end" width={340}>
                <Menu.Target><Button variant="default" size="xs" leftSection={<IconTemplate size={16} />}>Plantillas</Button></Menu.Target>
                <Menu.Dropdown>
                  {TEMPLATES.map((t) => (
                    <Menu.Item key={t.key} onClick={() => setOps((cur) => [...cur, ...wrap(t.build())])}>
                      <Text size="sm">{t.label}</Text>
                      <Text size="xs" c="dimmed">{t.description}</Text>
                    </Menu.Item>
                  ))}
                </Menu.Dropdown>
              </Menu>
              <Menu position="bottom-end" width={320}>
                <Menu.Target><Button size="xs" leftSection={<IconPlus size={16} />}>Agregar operación</Button></Menu.Target>
                <Menu.Dropdown>
                  <ScrollArea.Autosize mah={360} type="auto">
                    {OP_GROUPS.map((g) => (
                      <div key={g.label}>
                        <Menu.Label>{g.label}</Menu.Label>
                        {g.types.map((t) => meta.operations.find((o) => o.type === t)).filter(Boolean).map((o) => (
                          <Menu.Item key={o!.type} color={o!.destructive ? "red" : undefined} onClick={() => setOps((cur) => [...cur, ...wrap([emptyOperation(o!.type)])])}>
                            {o!.label}
                          </Menu.Item>
                        ))}
                      </div>
                    ))}
                  </ScrollArea.Autosize>
                </Menu.Dropdown>
              </Menu>
            </Group>
          </Group>

          {ops.length === 0 && <Alert color="gray">Agrega una operación, usa una plantilla o importa un script SQL existente. También puedes iniciar desde el explorador con los botones “Solicitar…”.</Alert>}

          {ops.map((item, idx) => (
            <Card key={item.key} withBorder style={destructive(item.op.type) ? { borderColor: "var(--mantine-color-red-4)" } : undefined}>
              <Group justify="space-between" mb="sm">
                <Group gap="xs">
                  <Badge variant="filled" color="gray" circle>{idx + 1}</Badge>
                  <Text fw={600}>{opLabel(item.op.type)}</Text>
                  {destructive(item.op.type) && <Badge color="red" variant="light" size="sm">destructiva</Badge>}
                </Group>
                <Group gap={4}>
                  <Tooltip label="Subir"><ActionIcon variant="subtle" disabled={idx === 0} onClick={() => move(idx, -1)}><IconArrowUp size={16} /></ActionIcon></Tooltip>
                  <Tooltip label="Bajar"><ActionIcon variant="subtle" disabled={idx === ops.length - 1} onClick={() => move(idx, 1)}><IconArrowDown size={16} /></ActionIcon></Tooltip>
                  <Tooltip label="Duplicar"><ActionIcon variant="subtle" onClick={() => setOps((cur) => [...cur.slice(0, idx + 1), ...wrap([structuredClone(item.op)]), ...cur.slice(idx + 1)])}><IconCopy size={16} /></ActionIcon></Tooltip>
                  <Tooltip label="Quitar"><ActionIcon variant="subtle" color="red" onClick={() => setOps((cur) => cur.filter((o) => o.key !== item.key))}><IconTrash size={16} /></ActionIcon></Tooltip>
                </Group>
              </Group>
              {item.notes && item.notes.length > 0 && (
                <Alert color="gray" variant="light" py={4} mb="sm" title="Importado del script">
                  {item.notes.map((n, i) => <Text key={i} size="xs">• {n}</Text>)}
                </Alert>
              )}
              <OperationEditor op={item.op} onChange={(op) => updateOp(item.key, op)} connectionId={connectionId} meta={meta} />
              {errorsFor(idx + 1).map((e, i) => <Text key={i} c="red" size="sm" mt={6}>• {e.field ? `${e.field}: ` : ""}{e.message}</Text>)}
              {findingsFor(idx + 1).map((f, i) => (
                <Text key={i} size="sm" mt={6} c={f.level === "error" ? "red" : f.level === "warning" ? "orange" : "dimmed"}>
                  {f.level === "error" ? "✖" : f.level === "warning" ? "⚠" : "ℹ"} {f.message}
                </Text>
              ))}
            </Card>
          ))}

          <Group justify="flex-end">
            <Button variant="default" onClick={() => nav(-1)}>Cancelar</Button>
            <Button variant="light" loading={saving} disabled={!canSave} onClick={() => save(false)}>Guardar borrador</Button>
            <Tooltip label={blocking ? "Hay errores en la validación; puedes enviarla, pero el DBA los verá" : "El DBA recibirá la solicitud para aprobación"}>
              <Button loading={saving} disabled={!canSave} leftSection={<IconSend size={16} />} color={blocking ? "orange" : undefined} onClick={() => save(true)}>
                Enviar a aprobación
              </Button>
            </Tooltip>
          </Group>
        </Stack>
      </Grid.Col>

      <ImportSqlModal opened={importOpen} onClose={() => setImportOpen(false)} connectionId={connectionId} meta={meta}
        onImport={(imported) => setOps((cur) => [...cur, ...wrap(imported.map((i) => i.op), imported.map((i) => i.notes))])} />

      <Grid.Col span={{ base: 12, xl: 5 }}>
        <Card withBorder style={{ position: "sticky", top: 70 }}>
          <Group justify="space-between" mb="xs">
            <Title order={5}>SQL que se ejecutará</Title>
            {preview.isFetching && <Loader size="xs" />}
          </Group>
          {ops.length === 0 && <Text c="dimmed" size="sm">Aquí verás el script generado y la validación contra la base.</Text>}
          {previewError && (
            <Alert color="red" mb="sm" title={previewError.message}>
              {previewError.details?.map((d, i) => <Text key={i} size="sm">#{d.op_seq} {d.field && `${d.field}: `}{d.message}</Text>)}
            </Alert>
          )}
          {!previewError && preview.data && (
            <>
              {preview.data.findings.length > 0 && (
                <Stack gap={4} mb="sm">
                  {preview.data.findings.map((f, i) => (
                    <Alert key={i} py={4} color={f.level === "error" ? "red" : f.level === "warning" ? "orange" : "blue"} variant="light">
                      <Text size="xs">#{f.op_seq} {f.message}</Text>
                    </Alert>
                  ))}
                </Stack>
              )}
              <SqlBlock sql={preview.data.script} maxHeight={560} />
            </>
          )}
        </Card>
      </Grid.Col>
    </Grid>
  );
}
