import {
  ActionIcon, Alert, Badge, Button, Card, FileButton, Group, Modal, NumberInput, PasswordInput, Select, SimpleGrid,
  Stack, Switch, Table, Tabs, Text, Textarea, TextInput, Title,
} from "@mantine/core";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { IconDatabaseExport, IconDatabaseImport, IconEdit, IconPlugConnected, IconPlus, IconTrash } from "@tabler/icons-react";
import { useState } from "react";
import { api, Connection, download, Project, useConnections, useMe, useProjects, useSchemas } from "../api";
import { EnvBadge, fmtDate, notifyError, notifyOk } from "../ui";
import UsersRolesAdmin from "./UsersRolesAdmin";

export default function AdminPage() {
  const { data: me } = useMe();
  if (!me?.is_dba) return <Alert color="red">Requiere rol DBA.</Alert>;
  return (
    <>
      <Title order={3} mb="sm">Administración</Title>
      <Tabs defaultValue={me.is_admin ? "connections" : "projects"} keepMounted={false}>
        <Tabs.List mb="md">
          {me.is_admin && <Tabs.Tab value="connections">Conexiones</Tabs.Tab>}
          {me.is_admin && <Tabs.Tab value="users">Usuarios y roles</Tabs.Tab>}
          <Tabs.Tab value="projects">Proyectos</Tabs.Tab>
          <Tabs.Tab value="scopes">Alcances de DBAs</Tabs.Tab>
          {me.is_admin && <Tabs.Tab value="backup">Respaldo de metadata</Tabs.Tab>}
        </Tabs.List>
        <Tabs.Panel value="connections"><ConnectionsAdmin /></Tabs.Panel>
        <Tabs.Panel value="users"><UsersRolesAdmin /></Tabs.Panel>
        <Tabs.Panel value="projects"><ProjectsAdmin /></Tabs.Panel>
        <Tabs.Panel value="scopes"><ScopesAdmin canEdit={me.is_admin} /></Tabs.Panel>
        <Tabs.Panel value="backup"><BackupAdmin /></Tabs.Panel>
      </Tabs>
    </>
  );
}

// ------------------------------------------------------------------ conexiones
type ConnForm = Omit<Connection, "id" | "has_exec_account" | "server_version"> & { catalog_password: string; exec_password: string };
const emptyConn: ConnForm = {
  name: "", engine: "oracle", environment: "DEV", description: "", host: "", port: 1521, service_name: "",
  catalog_user: "DBAMGR_CATALOG", catalog_password: "", exec_user: "DBAMGR_EXEC", exec_password: "",
  allow_execution: false, use_tls: false, required_approvals: 1, active: true,
};

function ConnectionsAdmin() {
  const qc = useQueryClient();
  const { data } = useQuery({ queryKey: ["admin", "connections"], queryFn: () => api.get<Connection[]>("/api/admin/connections") });
  const [edit, setEdit] = useState<{ id?: number; form: ConnForm } | null>(null);
  const [testing, setTesting] = useState<string | null>(null);

  const save = async () => {
    if (!edit) return;
    const body = { ...edit.form, catalog_password: edit.form.catalog_password || null, exec_password: edit.form.exec_password || null, exec_user: edit.form.exec_user || null };
    try {
      if (edit.id) await api.put(`/api/admin/connections/${edit.id}`, body);
      else await api.post("/api/admin/connections", body);
      notifyOk("Conexión guardada");
      setEdit(null);
      qc.invalidateQueries({ queryKey: ["admin", "connections"] });
      qc.invalidateQueries({ queryKey: ["connections"] });
    } catch (e) {
      notifyError(e);
    }
  };
  const test = async (c: Connection, role: "catalog" | "exec") => {
    setTesting(`${c.id}-${role}`);
    try {
      const r = await api.post<{ ok: boolean; error?: string; banner?: string; version?: string; supported?: boolean; container?: string; session_privileges?: string[] }>(`/api/admin/connections/${c.id}/test?role=${role}`);
      if (r.ok && r.supported === false) notifyError(new Error(`Oracle ${r.version} no está soportado: se requiere 12.1 o superior`));
      else if (r.ok) notifyOk(`${role === "catalog" ? "Catálogo" : "Ejecutora"} OK · Oracle ${r.version} · ${r.container} · ${r.session_privileges?.length} privilegios`);
      else notifyError(new Error(r.error));
    } catch (e) {
      notifyError(e);
    } finally {
      setTesting(null);
      qc.invalidateQueries({ queryKey: ["admin", "connections"] });
      qc.invalidateQueries({ queryKey: ["connections"] });
    }
  };
  const f = edit?.form;
  const setF = (patch: Partial<ConnForm>) => setEdit((cur) => (cur ? { ...cur, form: { ...cur.form, ...patch } } : cur));

  return (
    <Card withBorder>
      <Group justify="space-between" mb="sm">
        <Text size="sm" c="dimmed">Cada conexión es una base independiente (puede estar en otro servidor). La cuenta de catálogo solo lee el diccionario; la ejecutora aplica cambios aprobados.</Text>
        <Button size="xs" leftSection={<IconPlus size={16} />} onClick={() => setEdit({ form: emptyConn })}>Nueva conexión</Button>
      </Group>
      <Table>
        <Table.Thead><Table.Tr><Table.Th>Nombre</Table.Th><Table.Th>Destino</Table.Th><Table.Th>Cuentas</Table.Th><Table.Th>Ejecución</Table.Th><Table.Th /></Table.Tr></Table.Thead>
        <Table.Tbody>
          {data?.map((c) => (
            <Table.Tr key={c.id} style={{ opacity: c.active ? 1 : 0.5 }}>
              <Table.Td><Group gap={6}><EnvBadge env={c.environment} /><Text fw={500} size="sm">{c.name}</Text></Group><Text size="xs" c="dimmed">{c.description}</Text></Table.Td>
              <Table.Td ff="monospace" fz="sm">{c.host}:{c.port}/{c.service_name}<Text size="xs" c="dimmed">{c.server_version ? `Oracle ${c.server_version}` : "versión sin detectar (prueba la conexión)"}</Text></Table.Td>
              <Table.Td fz="sm">{c.catalog_user}{c.exec_user ? ` · ${c.exec_user}` : ""}</Table.Td>
              <Table.Td>
                <Badge variant="light" color={c.allow_execution ? "teal" : "gray"}>{c.allow_execution ? "desde la app" : "solo script"}</Badge>
                <Text size="xs" c="dimmed">{c.required_approvals} aprobación(es)</Text>
              </Table.Td>
              <Table.Td>
                <Group gap={4} justify="flex-end">
                  <Button size="compact-xs" variant="light" leftSection={<IconPlugConnected size={14} />} loading={testing === `${c.id}-catalog`} onClick={() => test(c, "catalog")}>Catálogo</Button>
                  {c.has_exec_account && <Button size="compact-xs" variant="light" color="orange" leftSection={<IconPlugConnected size={14} />} loading={testing === `${c.id}-exec`} onClick={() => test(c, "exec")}>Ejecutora</Button>}
                  <ActionIcon variant="subtle" onClick={() => setEdit({ id: c.id, form: { ...c, description: c.description ?? "", exec_user: c.exec_user ?? "", catalog_password: "", exec_password: "" } })}><IconEdit size={16} /></ActionIcon>
                </Group>
              </Table.Td>
            </Table.Tr>
          ))}
        </Table.Tbody>
      </Table>

      <Modal opened={!!edit} onClose={() => setEdit(null)} title={edit?.id ? "Editar conexión" : "Nueva conexión"} size="lg">
        {f && (
          <Stack gap="sm">
            <SimpleGrid cols={3}>
              <TextInput label="Nombre" value={f.name} onChange={(e) => setF({ name: e.currentTarget.value })} required />
              <Select label="Motor" data={[{ value: "oracle", label: "Oracle" }]} value={f.engine} onChange={(v) => setF({ engine: v! })} allowDeselect={false} />
              <Select label="Ambiente" data={["DEV", "QA", "UAT", "PROD"]} value={f.environment} onChange={(v) => setF({ environment: v! })} allowDeselect={false} />
            </SimpleGrid>
            <TextInput label="Descripción" value={f.description ?? ""} onChange={(e) => setF({ description: e.currentTarget.value })} />
            <SimpleGrid cols={3}>
              <TextInput label="Host" value={f.host} onChange={(e) => setF({ host: e.currentTarget.value })} required />
              <NumberInput label="Puerto" value={f.port} onChange={(v) => setF({ port: Number(v) })} />
              <TextInput label="Service name" value={f.service_name} onChange={(e) => setF({ service_name: e.currentTarget.value })} required />
            </SimpleGrid>
            <SimpleGrid cols={2}>
              <TextInput label="Usuario de catálogo" description="CREATE SESSION + SELECT_CATALOG_ROLE" value={f.catalog_user} onChange={(e) => setF({ catalog_user: e.currentTarget.value })} required />
              <PasswordInput label="Contraseña de catálogo" description={edit?.id ? "Vacío = conservar la actual" : " "} value={f.catalog_password} onChange={(e) => setF({ catalog_password: e.currentTarget.value })} />
              <TextInput label="Usuario ejecutor (opcional)" description="Privilegios ANY de DDL; solo ejecuta lo aprobado" value={f.exec_user ?? ""} onChange={(e) => setF({ exec_user: e.currentTarget.value })} />
              <PasswordInput label="Contraseña ejecutora" description={edit?.id ? "Vacío = conservar la actual" : " "} value={f.exec_password} onChange={(e) => setF({ exec_password: e.currentTarget.value })} />
            </SimpleGrid>
            <Group>
              <Switch label="Permitir ejecución desde DBA Manager" description="Además, cada DBA debe activarla para sí mismo" checked={f.allow_execution} disabled={!f.exec_user} onChange={(e) => setF({ allow_execution: e.currentTarget.checked })} />
              <Switch label="Conexión cifrada (TLS / tcps)" checked={f.use_tls} onChange={(e) => setF({ use_tls: e.currentTarget.checked })} />
              <NumberInput label="Aprobaciones requeridas" w={170} min={1} max={5} value={f.required_approvals} onChange={(v) => setF({ required_approvals: Number(v) })} />
              <Switch label="Activa" checked={f.active} onChange={(e) => setF({ active: e.currentTarget.checked })} />
            </Group>
            <Group justify="flex-end"><Button variant="default" onClick={() => setEdit(null)}>Cancelar</Button><Button onClick={save}>Guardar</Button></Group>
          </Stack>
        )}
      </Modal>
    </Card>
  );
}

// ------------------------------------------------------------------ proyectos
function ProjectsAdmin() {
  const qc = useQueryClient();
  const { data } = useProjects();
  const [edit, setEdit] = useState<Partial<Project> | null>(null);
  const save = async () => {
    if (!edit) return;
    const body = { code: edit.code, name: edit.name, description: edit.description || null, owner: edit.owner || null, active: edit.active ?? true };
    try {
      if (edit.id) await api.put(`/api/admin/projects/${edit.id}`, body);
      else await api.post("/api/admin/projects", body);
      setEdit(null);
      qc.invalidateQueries({ queryKey: ["projects"] });
    } catch (e) {
      notifyError(e);
    }
  };
  return (
    <Card withBorder>
      <Group justify="space-between" mb="sm">
        <Text size="sm" c="dimmed">Las solicitudes se ligan a un proyecto para saber para qué se creó cada objeto.</Text>
        <Button size="xs" leftSection={<IconPlus size={16} />} onClick={() => setEdit({ active: true })}>Nuevo proyecto</Button>
      </Group>
      <Table>
        <Table.Tbody>
          {data?.map((p) => (
            <Table.Tr key={p.id} style={{ opacity: p.active ? 1 : 0.5 }}>
              <Table.Td ff="monospace" fz="sm">{p.code}</Table.Td>
              <Table.Td><Text size="sm" fw={500}>{p.name}</Text><Text size="xs" c="dimmed">{p.description}</Text></Table.Td>
              <Table.Td fz="sm">{p.owner}</Table.Td>
              <Table.Td><ActionIcon variant="subtle" onClick={() => setEdit(p)}><IconEdit size={16} /></ActionIcon></Table.Td>
            </Table.Tr>
          ))}
        </Table.Tbody>
      </Table>
      <Modal opened={!!edit} onClose={() => setEdit(null)} title="Proyecto">
        {edit && (
          <Stack gap="sm">
            <TextInput label="Código" value={edit.code ?? ""} onChange={(e) => setEdit({ ...edit, code: e.currentTarget.value.toUpperCase() })} required />
            <TextInput label="Nombre" value={edit.name ?? ""} onChange={(e) => setEdit({ ...edit, name: e.currentTarget.value })} required />
            <Textarea label="Descripción" value={edit.description ?? ""} onChange={(e) => setEdit({ ...edit, description: e.currentTarget.value })} />
            <TextInput label="Responsable" value={edit.owner ?? ""} onChange={(e) => setEdit({ ...edit, owner: e.currentTarget.value })} />
            <Switch label="Activo" checked={edit.active ?? true} onChange={(e) => setEdit({ ...edit, active: e.currentTarget.checked })} />
            <Group justify="flex-end"><Button onClick={save}>Guardar</Button></Group>
          </Stack>
        )}
      </Modal>
    </Card>
  );
}

// ------------------------------------------------------------------ alcances
type Scope = { id: number; username: string; connection_id: number; schema_name: string; granted_by: string; granted_at: string };
type AppUser = { username: string; full_name: string; roles: string[]; last_seen: string };

function ScopesAdmin({ canEdit }: { canEdit: boolean }) {
  const qc = useQueryClient();
  const { data: scopes } = useQuery({ queryKey: ["admin", "scopes"], queryFn: () => api.get<Scope[]>("/api/admin/scopes") });
  const { data: users } = useQuery({ queryKey: ["admin", "users"], queryFn: () => api.get<AppUser[]>("/api/admin/users") });
  const { data: connections } = useConnections();
  const [form, setForm] = useState<{ username: string | null; connection_id: string | null; schema_name: string }>({ username: null, connection_id: null, schema_name: "" });
  const { data: schemas } = useSchemas(form.connection_id ? Number(form.connection_id) : null);
  const connName = (id: number) => connections?.find((c) => c.id === id)?.name ?? id;

  const add = async () => {
    try {
      await api.post("/api/admin/scopes", { ...form, connection_id: Number(form.connection_id) });
      setForm({ ...form, schema_name: "" });
      qc.invalidateQueries({ queryKey: ["admin", "scopes"] });
    } catch (e) {
      notifyError(e);
    }
  };
  const remove = async (id: number) => {
    try {
      await api.del(`/api/admin/scopes/${id}`);
      qc.invalidateQueries({ queryKey: ["admin", "scopes"] });
    } catch (e) {
      notifyError(e);
    }
  };

  return (
    <Card withBorder>
      <Text size="sm" c="dimmed" mb="sm">
        Los usuarios con rol <b>dba_admin</b> aprueban todo. Los de rol <b>dba</b> solo aprueban/ejecutan solicitudes cuyos esquemas tengan asignados aquí
        (<Text span ff="monospace">*</Text> = todos los esquemas de esa conexión). Los roles se asignan en la pestaña “Usuarios y roles”.
      </Text>
      {canEdit && (
        <Group align="flex-end" mb="md">
          <Select label="Usuario" searchable w={220} data={(users ?? []).map((u) => ({ value: u.username, label: `${u.username}${u.roles.includes("dba") ? " (dba)" : ""}` }))} value={form.username} onChange={(v) => setForm({ ...form, username: v })} />
          <Select label="Conexión" w={200} data={(connections ?? []).map((c) => ({ value: String(c.id), label: c.name }))} value={form.connection_id} onChange={(v) => setForm({ ...form, connection_id: v })} />
          <Select label="Esquema" searchable w={200} data={["*", ...(schemas ?? []).map((s) => s.name)]} value={form.schema_name || null} onChange={(v) => setForm({ ...form, schema_name: v ?? "" })} />
          <Button disabled={!form.username || !form.connection_id || !form.schema_name} onClick={add}>Asignar</Button>
        </Group>
      )}
      <Table>
        <Table.Thead><Table.Tr><Table.Th>DBA</Table.Th><Table.Th>Conexión</Table.Th><Table.Th>Esquema</Table.Th><Table.Th>Asignado por</Table.Th><Table.Th /></Table.Tr></Table.Thead>
        <Table.Tbody>
          {scopes?.map((s) => (
            <Table.Tr key={s.id}>
              <Table.Td fz="sm">{s.username}</Table.Td>
              <Table.Td fz="sm">{connName(s.connection_id)}</Table.Td>
              <Table.Td ff="monospace" fz="sm">{s.schema_name}</Table.Td>
              <Table.Td fz="xs" c="dimmed">{s.granted_by} · {fmtDate(s.granted_at)}</Table.Td>
              <Table.Td>{canEdit && <ActionIcon variant="subtle" color="red" onClick={() => remove(s.id)}><IconTrash size={16} /></ActionIcon>}</Table.Td>
            </Table.Tr>
          ))}
        </Table.Tbody>
      </Table>
      <Text size="xs" c="dimmed" mt="sm">Los usuarios aparecen en la lista después de su primer inicio de sesión.</Text>
    </Card>
  );
}

// ------------------------------------------------------------------ respaldo
function BackupAdmin() {
  const [replace, setReplace] = useState(false);
  const [result, setResult] = useState<Record<string, number> | null>(null);
  const restore = async (file: File | null) => {
    if (!file) return;
    if (!confirm(replace ? "Se BORRARÁ la metadata actual y se reemplazará por el respaldo. ¿Continuar?" : "¿Importar el respaldo en la metadata (debe estar vacía)?")) return;
    const fd = new FormData();
    fd.append("file", file);
    try {
      const r = await api.upload<{ rows: Record<string, number> }>(`/api/admin/restore?replace=${replace}`, fd);
      setResult(r.rows);
      notifyOk("Metadata restaurada");
    } catch (e) {
      notifyError(e);
    }
  };
  return (
    <Stack>
      <Card withBorder>
        <Title order={5}>Exportar</Title>
        <Text size="sm" c="dimmed" mb="sm">
          JSON portable con toda la metadata (conexiones, proyectos, alcances, solicitudes, aprobaciones, comentarios, ejecuciones y bitácora).
          Las contraseñas de conexiones van cifradas: para restaurar se necesita la misma <code>SECRETS_KEY</code>.
          Sirve para respaldar y para migrar la metadata a otra instancia u otro motor.
        </Text>
        <Button leftSection={<IconDatabaseExport size={16} />} onClick={() => download("/api/admin/backup", `dbamanager-metadata-${new Date().toISOString().slice(0, 10)}.json`).catch(notifyError)}>
          Descargar respaldo
        </Button>
      </Card>
      <Card withBorder>
        <Title order={5}>Restaurar / migrar</Title>
        <Text size="sm" c="dimmed" mb="sm">Importa un respaldo JSON. Por seguridad solo se importa sobre metadata vacía, salvo que actives “reemplazar”.</Text>
        <Group>
          <Switch color="red" label="Reemplazar metadata existente" checked={replace} onChange={(e) => setReplace(e.currentTarget.checked)} />
          <FileButton onChange={restore} accept="application/json">
            {(props) => <Button {...props} variant="light" color={replace ? "red" : undefined} leftSection={<IconDatabaseImport size={16} />}>Seleccionar archivo…</Button>}
          </FileButton>
        </Group>
        {result && <Text size="sm" mt="sm">Importado: {Object.entries(result).map(([k, v]) => `${k}=${v}`).join(", ")}</Text>}
      </Card>
      <Alert color="blue" title="Respaldo físico con Data Pump">
        Además del JSON, <code>scripts/backup-metadata.sh</code> genera un export Data Pump (expdp) del esquema DBAMGR en <code>./backups</code>.
      </Alert>
    </Stack>
  );
}
