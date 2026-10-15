import { Card, Code, Group, Loader, ScrollArea, Select, Table, Text, TextInput, Title } from "@mantine/core";
import { useDebouncedValue } from "@mantine/hooks";
import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { Link } from "react-router-dom";
import { api } from "../api";
import { ACTION_LABELS, fmtDate } from "../ui";

type AuditRow = { id: number; at: string; username: string; action: string; entity_type: string; entity_id: string | null; details: Record<string, unknown> | null; ip: string | null };

export default function AuditPage() {
  const [username, setUsername] = useState("");
  const [entity, setEntity] = useState<string | null>(null);
  const [du] = useDebouncedValue(username, 400);
  const qs = new URLSearchParams();
  if (du) qs.set("username", du);
  if (entity) qs.set("entity_type", entity);
  const { data, isLoading, error } = useQuery({ queryKey: ["audit", qs.toString()], queryFn: () => api.get<AuditRow[]>(`/api/admin/audit?${qs}`) });

  return (
    <>
      <Title order={3} mb="sm">Bitácora</Title>
      <Group mb="sm">
        <TextInput placeholder="Usuario" value={username} onChange={(e) => setUsername(e.currentTarget.value)} w={200} />
        <Select placeholder="Entidad" clearable w={200} value={entity} onChange={setEntity}
          data={[{ value: "request", label: "Solicitudes" }, { value: "connection", label: "Conexiones" }, { value: "scope", label: "Alcances" }, { value: "project", label: "Proyectos" }, { value: "metadata", label: "Metadata" }]} />
      </Group>
      <Card withBorder p={0}>
        {isLoading && <Loader m="md" />}
        {error && <Text c="red" p="md">{(error as Error).message}</Text>}
        <ScrollArea>
          <Table striped miw={900}>
            <Table.Thead><Table.Tr><Table.Th>Fecha</Table.Th><Table.Th>Usuario</Table.Th><Table.Th>Acción</Table.Th><Table.Th>Entidad</Table.Th><Table.Th>Detalle</Table.Th><Table.Th>IP</Table.Th></Table.Tr></Table.Thead>
            <Table.Tbody>
              {data?.map((r) => (
                <Table.Tr key={r.id}>
                  <Table.Td fz="sm" style={{ whiteSpace: "nowrap" }}>{fmtDate(r.at)}</Table.Td>
                  <Table.Td fz="sm">{r.username}</Table.Td>
                  <Table.Td fz="sm">{ACTION_LABELS[r.action] ?? r.action}</Table.Td>
                  <Table.Td fz="sm">
                    {r.entity_type === "request" && r.entity_id ? <Link to={`/requests/${r.entity_id}`}>SOL-{r.entity_id.padStart(6, "0")}</Link> : `${r.entity_type} ${r.entity_id ?? ""}`}
                  </Table.Td>
                  <Table.Td>{r.details && <Code style={{ whiteSpace: "pre-wrap", fontSize: 11 }}>{JSON.stringify(r.details)}</Code>}</Table.Td>
                  <Table.Td fz="xs" c="dimmed">{r.ip}</Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </ScrollArea>
      </Card>
    </>
  );
}
