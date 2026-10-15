import {
  Accordion, Alert, Badge, Button, Card, Code, Grid, Group, Loader, Modal, SegmentedControl, Stack, Table, Text,
  Textarea, TextInput, Timeline, Title,
} from "@mantine/core";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import {
  IconAlertTriangle, IconCheck, IconDownload, IconEdit, IconMessage, IconPlayerPlay, IconSend, IconShieldCheck, IconX,
} from "@tabler/icons-react";
import { useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { api, download, Finding, HistoryEvent, RequestDetail, useMeta } from "../api";
import { ACTION_LABELS, EnvBadge, fmtDate, notifyError, notifyOk, SqlBlock, StatusBadge } from "../ui";

type Secret = { operation: number; user: string; password: string };

export default function RequestDetailPage() {
  const { id } = useParams();
  const nav = useNavigate();
  const qc = useQueryClient();
  const { data: meta } = useMeta();
  const { data: r, isLoading, error } = useQuery({ queryKey: ["request", id], queryFn: () => api.get<RequestDetail>(`/api/requests/${id}`) });
  const history = useQuery({ queryKey: ["request", id, "history"], queryFn: () => api.get<HistoryEvent[]>(`/api/requests/${id}/history`) });
  const [findings, setFindings] = useState<Finding[] | null>(null);
  const [checking, setChecking] = useState(false);
  const [review, setReview] = useState<null | "APPROVED" | "REJECTED" | "CHANGES_REQUESTED">(null);
  const [executeOpen, setExecuteOpen] = useState(false);
  const [secrets, setSecrets] = useState<Secret[] | null>(null);
  const [comment, setComment] = useState("");
  const [busy, setBusy] = useState(false);

  if (isLoading) return <Loader />;
  if (error || !r) return <Alert color="red">{(error as Error)?.message ?? "No encontrada"}</Alert>;

  const refresh = (data?: RequestDetail) => {
    if (data) qc.setQueryData(["request", id], { ...r, ...data });
    qc.invalidateQueries({ queryKey: ["request", id] });
    qc.invalidateQueries({ queryKey: ["requests"] });
  };
  const run = async (fn: () => Promise<RequestDetail | void>, ok?: string) => {
    setBusy(true);
    try {
      const data = await fn();
      if (ok) notifyOk(ok);
      refresh(data || undefined);
      return true;
    } catch (e) {
      notifyError(e);
      return false;
    } finally {
      setBusy(false);
    }
  };
  const check = async () => {
    setChecking(true);
    try {
      setFindings(await api.get<Finding[]>(`/api/requests/${id}/preflight`));
    } catch (e) {
      notifyError(e);
    } finally {
      setChecking(false);
    }
  };
  const opLabel = (t: string) => meta?.operations.find((o) => o.type === t)?.label ?? t;
  const approvals = r.approvals.filter((a) => a.active && a.decision === "APPROVED").length;
  const p = r.permissions;

  return (
    <Stack>
      <Group justify="space-between" align="flex-start">
        <div>
          <Group gap="xs">
            <Text ff="monospace" c="dimmed">{r.code}</Text>
            <StatusBadge status={r.status} />
            <EnvBadge env={r.connection.environment} />
          </Group>
          <Title order={3}>{r.title}</Title>
          <Text size="sm" c="dimmed">
            Solicitada por <b>{r.requested_by}</b> el {fmtDate(r.created_at)} · {r.connection.name}
            {r.project ? ` · proyecto ${r.project.code}` : ""}{r.ticket_ref ? ` · ${r.ticket_ref}` : ""}
          </Text>
        </div>
        <Group gap="xs">
          <Button variant="default" size="xs" leftSection={<IconDownload size={16} />} onClick={() => download(`/api/requests/${id}/script`, `${r.code}.sql`).catch(notifyError)}>Script .sql</Button>
          {p.edit && <Button variant="default" size="xs" leftSection={<IconEdit size={16} />} onClick={() => nav(`/requests/${id}/edit`)}>Editar</Button>}
          {p.submit && <Button size="xs" leftSection={<IconSend size={16} />} loading={busy} onClick={() => run(() => api.post(`/api/requests/${id}/submit`), "Enviada a aprobación")}>Enviar a aprobación</Button>}
          {p.cancel && <Button size="xs" color="gray" variant="light" loading={busy} onClick={() => { if (confirm("¿Cancelar la solicitud?")) run(() => api.post(`/api/requests/${id}/cancel`, {}), "Cancelada"); }}>Cancelar</Button>}
        </Group>
      </Group>

      {p.review && (
        <Alert color="yellow" icon={<IconShieldCheck />} title="Tu revisión está pendiente">
          <Text size="sm" mb="xs">Revisa el SQL y valida contra la base antes de decidir. Se requieren {r.required_approvals} aprobación(es); van {approvals}.</Text>
          <Group gap="xs">
            <Button size="xs" color="green" leftSection={<IconCheck size={16} />} onClick={() => setReview("APPROVED")}>Aprobar</Button>
            <Button size="xs" color="orange" variant="light" onClick={() => setReview("CHANGES_REQUESTED")}>Pedir cambios</Button>
            <Button size="xs" color="red" variant="light" leftSection={<IconX size={16} />} onClick={() => setReview("REJECTED")}>Rechazar</Button>
          </Group>
        </Alert>
      )}
      {p.execute && (
        <Alert color="teal" icon={<IconPlayerPlay />} title={r.status === "FAILED" ? "La ejecución falló: puedes reintentar las operaciones pendientes" : "Aprobada: lista para ejecutar"}>
          <Button size="xs" color="teal" onClick={() => setExecuteOpen(true)}>Ejecutar…</Button>
        </Alert>
      )}

      <Grid>
        <Grid.Col span={{ base: 12, lg: 8 }}>
          <Stack>
            <Card withBorder>
              <Text fw={600} size="sm">¿Por qué se necesita?</Text>
              <Text size="sm" style={{ whiteSpace: "pre-wrap" }} mb="sm">{r.reason}</Text>
              {r.purpose && (
                <>
                  <Text fw={600} size="sm">¿Para qué / con qué fin?</Text>
                  <Text size="sm" style={{ whiteSpace: "pre-wrap" }}>{r.purpose}</Text>
                </>
              )}
            </Card>

            <Card withBorder>
              <Group justify="space-between" mb="sm">
                <Title order={5}>Operaciones ({r.operations.length})</Title>
                <Button size="xs" variant="light" loading={checking} onClick={check}>Validar contra la base</Button>
              </Group>
              {findings && (
                <Stack gap={4} mb="sm">
                  {findings.length === 0 && <Alert color="green" py={4}>Sin observaciones: los objetos y usuarios referenciados son consistentes.</Alert>}
                  {findings.map((f, i) => (
                    <Alert key={i} py={4} color={f.level === "error" ? "red" : f.level === "warning" ? "orange" : "blue"} variant="light">
                      <Text size="xs">#{f.op_seq} {f.message}</Text>
                    </Alert>
                  ))}
                </Stack>
              )}
              <Accordion multiple defaultValue={r.operations.map((o) => String(o.id))} variant="separated">
                {r.operations.map((o) => (
                  <Accordion.Item key={o.id} value={String(o.id)}>
                    <Accordion.Control>
                      <Group gap="xs">
                        <Badge circle color="gray">{o.seq}</Badge>
                        <Text fw={500} size="sm">{opLabel(o.type)}</Text>
                        <Code>{o.schema}{o.object_name && o.object_name !== o.schema ? `.${o.object_name}` : ""}</Code>
                        <Badge size="xs" variant="light" color={o.status === "EXECUTED" ? "green" : o.status === "FAILED" ? "red" : "gray"}>{o.status}</Badge>
                        {o.status === "FAILED" && o.statements_done > 0 && (
                          <Text size="xs" c="dimmed">{o.statements_done}/{o.statements.length} sentencias aplicadas; el reintento sigue desde la {o.statements_done + 1}</Text>
                        )}
                      </Group>
                    </Accordion.Control>
                    <Accordion.Panel>
                      <SqlBlock sql={o.statements.map((s) => (s.plsql ? `${s.sql}\n/` : `${s.sql};`)).join("\n\n")} />
                      {o.message && o.status === "FAILED" && <Alert color="red" mt="xs" py={6}><Text size="xs" style={{ whiteSpace: "pre-wrap" }}>{o.message}</Text></Alert>}
                    </Accordion.Panel>
                  </Accordion.Item>
                ))}
              </Accordion>
            </Card>

            {r.executions.length > 0 && (
              <Card withBorder>
                <Title order={5} mb="sm">Ejecuciones</Title>
                {r.executions.map((e) => (
                  <div key={e.id} style={{ marginBottom: 12 }}>
                    <Group gap="xs" mb={4}>
                      <Badge color={e.status === "SUCCESS" ? "green" : "red"}>{e.status === "SUCCESS" ? "Exitosa" : "Falló"}</Badge>
                      <Badge variant="light">{e.mode === "AUTO" ? "desde DBA Manager" : "manual"}</Badge>
                      <Text size="sm">{e.executed_by} · {fmtDate(e.started_at)}</Text>
                    </Group>
                    {e.notes && <Text size="sm" mb={4}>Notas: {e.notes}</Text>}
                    {e.log && <SqlBlock sql={e.log} maxHeight={240} />}
                  </div>
                ))}
              </Card>
            )}

            <Card withBorder>
              <Title order={5} mb="sm">Comentarios</Title>
              {r.comments.length === 0 && <Text size="sm" c="dimmed" mb="sm">Sin comentarios.</Text>}
              {r.comments.map((c) => (
                <div key={c.id} style={{ marginBottom: 10 }}>
                  <Text size="xs" c="dimmed">{c.author} · {fmtDate(c.created_at)}</Text>
                  <Text size="sm" style={{ whiteSpace: "pre-wrap" }}>{c.body}</Text>
                </div>
              ))}
              <Textarea placeholder="Escribe un comentario o pregunta…" value={comment} onChange={(e) => setComment(e.currentTarget.value)} autosize minRows={2} />
              <Group justify="flex-end" mt="xs">
                <Button size="xs" leftSection={<IconMessage size={16} />} disabled={!comment.trim()} loading={busy}
                  onClick={async () => { if (await run(() => api.post<RequestDetail>(`/api/requests/${id}/comments`, { body: comment }))) setComment(""); }}>
                  Comentar
                </Button>
              </Group>
            </Card>
          </Stack>
        </Grid.Col>

        <Grid.Col span={{ base: 12, lg: 4 }}>
          <Stack>
            <Card withBorder>
              <Title order={5} mb="xs">Aprobaciones ({approvals}/{r.required_approvals})</Title>
              {r.approvals.length === 0 && <Text size="sm" c="dimmed">Aún sin revisiones.</Text>}
              {r.approvals.map((a, i) => (
                <div key={i} style={{ opacity: a.active ? 1 : 0.5, marginBottom: 8 }}>
                  <Group gap="xs">
                    <Badge size="sm" color={a.decision === "APPROVED" ? "green" : a.decision === "REJECTED" ? "red" : "orange"}>
                      {a.decision === "APPROVED" ? "Aprobó" : a.decision === "REJECTED" ? "Rechazó" : "Pidió cambios"}
                    </Badge>
                    <Text size="sm" fw={500}>{a.approver}</Text>
                    {!a.active && <Text size="xs" c="dimmed">(versión anterior)</Text>}
                  </Group>
                  <Text size="xs" c="dimmed">{fmtDate(a.decided_at)}</Text>
                  {a.comment && <Text size="sm">{a.comment}</Text>}
                </div>
              ))}
              <Text size="xs" c="dimmed" mt="sm">
                Pueden aprobar: DBAs principales{r.eligible_reviewers.length ? ` y ${r.eligible_reviewers.join(", ")}` : ""}.
              </Text>
            </Card>

            <Card withBorder>
              <Title order={5} mb="sm">Historial</Title>
              <Timeline bulletSize={14} lineWidth={2}>
                {(history.data ?? []).map((h, i) => (
                  <Timeline.Item key={i} title={<Text size="sm">{ACTION_LABELS[h.action] ?? h.action}</Text>}>
                    <Text size="xs" c="dimmed">{h.username} · {fmtDate(h.at)}</Text>
                    {typeof h.details?.comment === "string" && <Text size="xs">“{h.details.comment}”</Text>}
                    {typeof h.details?.status === "string" && <Text size="xs">Resultado: {h.details.status}</Text>}
                  </Timeline.Item>
                ))}
              </Timeline>
            </Card>
          </Stack>
        </Grid.Col>
      </Grid>

      <ReviewModal decision={review} onClose={() => setReview(null)} busy={busy}
        onSubmit={async (decision, c) => {
          if (await run(() => api.post<RequestDetail>(`/api/requests/${id}/review`, { decision, comment: c || null }), "Decisión registrada")) setReview(null);
        }} />

      <ExecuteModal opened={executeOpen} onClose={() => setExecuteOpen(false)} r={r}
        onDone={(data, s) => {
          setExecuteOpen(false);
          refresh(data);
          if (s && s.length) setSecrets(s);
        }} />

      <Modal opened={!!secrets} onClose={() => setSecrets(null)} title="Contraseñas generadas" closeOnClickOutside={false}>
        <Alert color="orange" icon={<IconAlertTriangle />} mb="sm">
          Se muestran una sola vez y no se guardan en DBA Manager. Entrégalas por un canal seguro.
        </Alert>
        <Table>
          <Table.Tbody>
            {secrets?.map((s) => (
              <Table.Tr key={s.user}><Table.Td ff="monospace">{s.user}</Table.Td><Table.Td><Code>{s.password}</Code></Table.Td></Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
        <Group justify="flex-end" mt="md"><Button onClick={() => setSecrets(null)}>Ya las guardé</Button></Group>
      </Modal>
    </Stack>
  );
}

function ReviewModal({ decision, onClose, onSubmit, busy }: { decision: null | "APPROVED" | "REJECTED" | "CHANGES_REQUESTED"; onClose: () => void; onSubmit: (d: string, c: string) => void; busy: boolean }) {
  const [c, setC] = useState("");
  const title = decision === "APPROVED" ? "Aprobar solicitud" : decision === "REJECTED" ? "Rechazar solicitud" : "Pedir cambios";
  return (
    <Modal opened={!!decision} onClose={onClose} title={title}>
      <Textarea label={decision === "APPROVED" ? "Comentario (opcional)" : "Motivo (obligatorio)"} value={c} onChange={(e) => setC(e.currentTarget.value)} autosize minRows={3} />
      <Group justify="flex-end" mt="md">
        <Button variant="default" onClick={onClose}>Cancelar</Button>
        <Button color={decision === "APPROVED" ? "green" : decision === "REJECTED" ? "red" : "orange"} loading={busy}
          disabled={decision !== "APPROVED" && !c.trim()} onClick={() => onSubmit(decision!, c)}>
          Confirmar
        </Button>
      </Group>
    </Modal>
  );
}

function ExecuteModal({ opened, onClose, r, onDone }: { opened: boolean; onClose: () => void; r: RequestDetail; onDone: (d: RequestDetail, secrets?: Secret[]) => void }) {
  const [mode, setMode] = useState<"AUTO" | "MANUAL">(r.permissions.auto_execute ? "AUTO" : "MANUAL");
  const [code, setCode] = useState("");
  const [notes, setNotes] = useState("");
  const [busy, setBusy] = useState(false);
  const [log, setLog] = useState<string | null>(null);
  const pending = r.operations.filter((o) => o.status !== "EXECUTED")
    .map((o) => ({ ...o, statements: o.statements.slice(o.statements_done ?? 0) }));

  const go = async () => {
    setBusy(true);
    try {
      if (mode === "AUTO") {
        const res = await api.post<{ status: string; log: string; secrets: Secret[]; request: RequestDetail }>(`/api/requests/${r.id}/execute`, { confirm_code: code });
        setLog(res.log);
        if (res.status === "EXECUTED") notifyOk(`${r.code} ejecutada correctamente`);
        else notifyError(new Error("La ejecución falló; revisa el log"));
        onDone(res.request, res.secrets);
      } else {
        const res = await api.post<RequestDetail>(`/api/requests/${r.id}/mark-executed`, { confirm_code: code, notes });
        notifyOk(`${r.code} registrada como ejecutada manualmente`);
        onDone(res);
      }
      setCode("");
    } catch (e) {
      notifyError(e);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Modal opened={opened} onClose={onClose} title={`Ejecutar ${r.code} en ${r.connection.name}`} size="xl">
      <Stack>
        <SegmentedControl value={mode} onChange={(v) => setMode(v as "AUTO" | "MANUAL")} data={[
          { value: "AUTO", label: "Ejecutar desde DBA Manager", disabled: !r.permissions.auto_execute },
          { value: "MANUAL", label: "Ya lo ejecuté manualmente" },
        ]} />
        {!r.permissions.auto_execute && <Text size="xs" c="dimmed">Esta conexión no permite ejecución desde DBA Manager; descarga el script, ejecútalo y regístralo aquí.</Text>}
        {mode === "AUTO" && (
          <Alert color={r.connection.environment === "PROD" ? "red" : "orange"} icon={<IconAlertTriangle />}>
            Se ejecutarán {pending.length} operación(es) con la cuenta privilegiada de <b>{r.connection.name}</b> ({r.connection.environment}).
            Se ejecutan en el orden mostrado, una por una. El DDL en Oracle hace commit automático: no hay rollback.
            Si una sentencia falla, todo se detiene ahí y el reintento continúa exactamente desde esa sentencia.
          </Alert>
        )}
        <SqlBlock sql={pending.map((o) => `-- [${o.seq}] ${o.type}\n` + o.statements.map((s) => (s.plsql ? `${s.sql}\n/` : `${s.sql};`)).join("\n")).join("\n\n")} maxHeight={300} />
        {mode === "MANUAL" && <Textarea label="Notas de ejecución" description="Dónde, cómo y con qué resultado se ejecutó" value={notes} onChange={(e) => setNotes(e.currentTarget.value)} autosize minRows={2} required />}
        <TextInput label={<>Para confirmar escribe <Code>{r.code}</Code></>} value={code} onChange={(e) => setCode(e.currentTarget.value)} />
        {log && <SqlBlock sql={log} maxHeight={200} />}
        <Group justify="flex-end">
          <Button variant="default" onClick={onClose}>Cerrar</Button>
          <Button color={mode === "AUTO" ? "red" : "teal"} loading={busy} disabled={code.trim().toUpperCase() !== r.code || (mode === "MANUAL" && !notes.trim())} onClick={go}>
            {mode === "AUTO" ? "Ejecutar ahora" : "Registrar ejecución manual"}
          </Button>
        </Group>
      </Stack>
    </Modal>
  );
}
