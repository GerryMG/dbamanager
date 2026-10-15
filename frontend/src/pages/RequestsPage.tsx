import { Badge, Button, Card, Group, Loader, ScrollArea, Select, Table, Tabs, Text, TextInput, Title } from "@mantine/core";
import { useDebouncedValue } from "@mantine/hooks";
import { useQuery } from "@tanstack/react-query";
import { IconPlus, IconSearch } from "@tabler/icons-react";
import { useState } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
import { api, RequestSummary, useConnections, useMe, useProjects } from "../api";
import { EnvBadge, fmtDate, STATUS, StatusBadge } from "../ui";

export default function RequestsPage() {
  const nav = useNavigate();
  const { data: me } = useMe();
  const [params, setParams] = useSearchParams();
  const view = params.get("view") ?? (me?.is_dba ? "to_review" : "mine");
  const [status, setStatus] = useState<string | null>(null);
  const [connectionId, setConnectionId] = useState<string | null>(null);
  const [projectId, setProjectId] = useState<string | null>(null);
  const [q, setQ] = useState("");
  const [dq] = useDebouncedValue(q, 400);
  const { data: connections } = useConnections();
  const { data: projects } = useProjects();

  const qs = new URLSearchParams({ view });
  if (status) qs.set("status", status);
  if (connectionId) qs.set("connection_id", connectionId);
  if (projectId) qs.set("project_id", projectId);
  if (dq) qs.set("q", dq);
  const { data, isLoading } = useQuery({
    queryKey: ["requests", qs.toString()],
    queryFn: () => api.get<RequestSummary[]>(`/api/requests?${qs}`),
  });

  return (
    <>
      <Group justify="space-between" mb="sm">
        <Title order={3}>Solicitudes</Title>
        <Button leftSection={<IconPlus size={16} />} onClick={() => nav("/requests/new")}>Nueva solicitud</Button>
      </Group>
      <Tabs value={view} onChange={(v) => setParams({ view: v! })} mb="sm">
        <Tabs.List>
          {me?.is_dba && <Tabs.Tab value="to_review">Por revisar</Tabs.Tab>}
          {me?.is_dba && <Tabs.Tab value="to_execute">Por ejecutar</Tabs.Tab>}
          <Tabs.Tab value="mine">Mis solicitudes</Tabs.Tab>
          <Tabs.Tab value="all">Todas</Tabs.Tab>
        </Tabs.List>
      </Tabs>
      <Group mb="sm" gap="xs">
        <TextInput placeholder="Título, esquema o solicitante" leftSection={<IconSearch size={16} />} value={q} onChange={(e) => setQ(e.currentTarget.value)} w={260} />
        <Select placeholder="Estado" clearable data={Object.entries(STATUS).map(([value, s]) => ({ value, label: s.label }))} value={status} onChange={setStatus} w={200} />
        <Select placeholder="Base de datos" clearable data={(connections ?? []).map((c) => ({ value: String(c.id), label: c.name }))} value={connectionId} onChange={setConnectionId} w={180} />
        <Select placeholder="Proyecto" clearable data={(projects ?? []).map((p) => ({ value: String(p.id), label: p.code }))} value={projectId} onChange={setProjectId} w={160} />
      </Group>
      <Card withBorder p={0}>
        {isLoading && <Loader m="md" />}
        {data && data.length === 0 && <Text p="md" c="dimmed">No hay solicitudes con estos filtros.</Text>}
        {data && data.length > 0 && (
          <ScrollArea>
            <Table highlightOnHover miw={900}>
              <Table.Thead>
                <Table.Tr>
                  <Table.Th>Código</Table.Th>
                  <Table.Th>Título</Table.Th>
                  <Table.Th>Estado</Table.Th>
                  <Table.Th>Base</Table.Th>
                  <Table.Th>Esquemas</Table.Th>
                  <Table.Th>Proyecto</Table.Th>
                  <Table.Th>Solicitó</Table.Th>
                  <Table.Th>Actualizada</Table.Th>
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {data.map((r) => (
                  <Table.Tr key={r.id} style={{ cursor: "pointer" }} onClick={() => nav(`/requests/${r.id}`)}>
                    <Table.Td ff="monospace" fz="sm">{r.code}</Table.Td>
                    <Table.Td>
                      <Text size="sm" fw={500}>{r.title}</Text>
                      <Text size="xs" c="dimmed">{r.operation_types.join(", ")}</Text>
                    </Table.Td>
                    <Table.Td><StatusBadge status={r.status} /></Table.Td>
                    <Table.Td><Group gap={4} wrap="nowrap"><EnvBadge env={r.connection.environment} /><Text size="sm">{r.connection.name}</Text></Group></Table.Td>
                    <Table.Td><Group gap={4}>{r.target_schemas.map((s) => <Badge key={s} size="sm" variant="outline">{s}</Badge>)}</Group></Table.Td>
                    <Table.Td fz="sm">{r.project?.code ?? "—"}</Table.Td>
                    <Table.Td fz="sm">{r.requested_by}</Table.Td>
                    <Table.Td fz="sm">{fmtDate(r.updated_at)}</Table.Td>
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </Table>
          </ScrollArea>
        )}
      </Card>
    </>
  );
}
