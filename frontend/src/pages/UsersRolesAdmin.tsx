import { Alert, Badge, Button, Card, Checkbox, Code, Group, Loader, ScrollArea, SimpleGrid, Table, Text, TextInput, Tooltip } from "@mantine/core";
import { useDebouncedValue } from "@mantine/hooks";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { IconSearch, IconShieldCheck } from "@tabler/icons-react";
import { useState } from "react";
import { api, useMe } from "../api";
import { fmtDate, notifyError, notifyOk } from "../ui";

interface Identity {
  issuer: string;
  realm: string;
  client_id: string;
  public_url: string;
  internal_url: string;
  roles_source: string;
  roles_client: string | null;
  role_names: Record<string, string>;
  management_enabled: boolean;
  management_mode: string;
  management_ok: boolean;
  management_error: string | null;
  missing_roles: string[];
}

interface IdpUser {
  id: string;
  username: string;
  email: string | null;
  full_name: string;
  enabled: boolean;
  roles: string[];
  direct_roles: string[];
  scopes: number;
  last_seen: string | null;
}

const ROLES = [
  { key: "solicitante", label: "Solicitante", help: "Explora estructura y crea solicitudes" },
  { key: "dba", label: "DBA", help: "Aprueba/ejecuta solo en los esquemas de sus alcances" },
  { key: "dba_admin", label: "DBA principal", help: "Aprueba y ejecuta cualquier solicitud" },
  { key: "admin", label: "Admin", help: "Conexiones, usuarios, roles, alcances y respaldos" },
];

export function IdentityCard() {
  const { data: id, isLoading } = useQuery({ queryKey: ["admin", "identity"], queryFn: () => api.get<Identity>("/api/admin/identity") });
  if (isLoading || !id) return <Loader size="sm" />;
  return (
    <Card withBorder mb="md">
      <Group justify="space-between" mb="xs">
        <Text fw={600}>Proveedor de identidad</Text>
        {id.management_ok ? <Badge color="green" leftSection={<IconShieldCheck size={12} />}>Administración de roles activa</Badge>
          : id.management_enabled ? <Badge color="red">Administración de roles con error</Badge>
          : <Badge color="gray">Roles solo desde Keycloak</Badge>}
        {id.management_enabled && (
          <Badge variant="outline" ml={6}>{id.management_mode === "token" ? "con tu usuario de Keycloak" : "con cuenta de servicio"}</Badge>
        )}
      </Group>
      <SimpleGrid cols={{ base: 1, md: 2 }} spacing={4}>
        <Text size="sm">Issuer: <Code>{id.issuer}</Code></Text>
        <Text size="sm">Cliente de inicio de sesión: <Code>{id.client_id}</Code></Text>
        <Text size="sm">URL interna (backend): <Code>{id.internal_url}</Code></Text>
        <Text size="sm">Roles desde: <Code>{id.roles_source === "client" ? `cliente ${id.roles_client}` : "realm"}</Code></Text>
        <Text size="sm">Nombres de rol: {Object.entries(id.role_names).map(([k, v]) => <Code key={k} mr={4}>{k}→{v}</Code>)}</Text>
      </SimpleGrid>
      {id.management_error && <Alert color="red" mt="sm" py={6}>{id.management_error}</Alert>}
      {id.missing_roles.length > 0 && <Alert color="orange" mt="sm" py={6}>Roles que no existen en Keycloak: {id.missing_roles.join(", ")}</Alert>}
      {!id.management_enabled && (
        <Alert color="blue" mt="sm" py={6}>
          La asignación de roles desde DBA Manager está deshabilitada (<Code>KEYCLOAK_ROLES_ADMIN_MODE=off</Code>). Los roles se asignan en la consola de Keycloak.
        </Alert>
      )}
      {id.management_mode === "token" && id.management_error && (
        <Text size="xs" c="dimmed" mt={4}>
          En modo token, cada admin de DBA Manager usa su propio usuario de Keycloak: en Keycloak, en tu realm, asígnale los roles
          <Code>view-users</Code>, <Code>query-users</Code> y <Code>manage-users</Code> del cliente <Code>realm-management</Code>
          (Users → tu usuario → Role mapping → Assign role → Filter by clients). Después cierra sesión y vuelve a entrar.
        </Text>
      )}
      <Text size="xs" c="dimmed" mt="xs">Todo esto se configura con variables de entorno (KEYCLOAK_*, OIDC_*, ROLE_*); ver README.</Text>
    </Card>
  );
}

export default function UsersRolesAdmin() {
  const qc = useQueryClient();
  const { data: me } = useMe();
  const { data: identity } = useQuery({ queryKey: ["admin", "identity"], queryFn: () => api.get<Identity>("/api/admin/identity") });
  const [search, setSearch] = useState("");
  const [dsearch] = useDebouncedValue(search, 400);
  const [edits, setEdits] = useState<Record<string, string[]>>({});
  const [saving, setSaving] = useState<string | null>(null);
  const enabled = !!identity?.management_ok;
  // en modo token los permisos los da el token del propio admin
  const { data: users, isLoading, error } = useQuery({
    queryKey: ["admin", "idp-users", dsearch],
    queryFn: () => api.get<IdpUser[]>(`/api/admin/idp-users?limit=100${dsearch ? `&search=${encodeURIComponent(dsearch)}` : ""}`),
    enabled,
  });

  const toggle = (u: IdpUser, role: string, on: boolean) => {
    const cur = edits[u.id] ?? u.direct_roles;
    setEdits({ ...edits, [u.id]: on ? [...new Set([...cur, role])] : cur.filter((r) => r !== role) });
  };
  const save = async (u: IdpUser) => {
    setSaving(u.id);
    try {
      const r = await api.put<{ roles: string[]; added: string[]; removed: string[]; note: string | null }>(`/api/admin/idp-users/${u.id}/roles`, { roles: edits[u.id] });
      notifyOk(`${u.username}: ${[...r.added.map((x) => `+${x}`), ...r.removed.map((x) => `−${x}`)].join(" ") || "sin cambios"}. Aplica en su próximo inicio de sesión o renovación de token.`);
      if (r.note) notifyError(new Error(r.note));
      const { [u.id]: _, ...rest } = edits;
      setEdits(rest);
      qc.invalidateQueries({ queryKey: ["admin", "idp-users"] });
      qc.invalidateQueries({ queryKey: ["admin", "users"] });
    } catch (e) {
      notifyError(e);
    } finally {
      setSaving(null);
    }
  };

  return (
    <>
      <IdentityCard />
      {enabled && (
        <Card withBorder>
          <Group justify="space-between" mb="sm">
            <TextInput placeholder="Buscar usuario, nombre o correo" leftSection={<IconSearch size={16} />} value={search} onChange={(e) => setSearch(e.currentTarget.value)} w={320} />
            <Text size="xs" c="dimmed">Los usuarios vienen de Keycloak (incluidos los federados por LDAP/AD).</Text>
          </Group>
          {isLoading && <Loader size="sm" />}
          {error && <Alert color="red">{(error as Error).message}</Alert>}
          <ScrollArea>
            <Table striped>
              <Table.Thead>
                <Table.Tr>
                  <Table.Th>Usuario</Table.Th>
                  {ROLES.map((r) => <Table.Th key={r.key}><Tooltip label={r.help}><span>{r.label}</span></Tooltip></Table.Th>)}
                  <Table.Th>Alcances</Table.Th>
                  <Table.Th>Último acceso</Table.Th>
                  <Table.Th />
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {users?.map((u) => {
                  const current = edits[u.id] ?? u.direct_roles;
                  const dirty = !!edits[u.id] && [...current].sort().join() !== [...u.direct_roles].sort().join();
                  return (
                    <Table.Tr key={u.id} style={{ opacity: u.enabled ? 1 : 0.5 }}>
                      <Table.Td>
                        <Text size="sm" fw={500}>{u.username}{!u.enabled && <Badge size="xs" ml={4} color="gray">deshabilitado</Badge>}</Text>
                        <Text size="xs" c="dimmed">{u.full_name}{u.email ? ` · ${u.email}` : ""}</Text>
                      </Table.Td>
                      {ROLES.map((r) => {
                        const inherited = u.roles.includes(r.key) && !u.direct_roles.includes(r.key);
                        const self = u.username === me?.username && r.key === "admin";
                        return (
                          <Table.Td key={r.key}>
                            <Tooltip label={inherited ? "Heredado de un grupo o rol compuesto en Keycloak" : self ? "No puedes quitarte tu propio rol admin" : r.help} disabled={!inherited && !self}>
                              <Checkbox checked={current.includes(r.key) || inherited} disabled={inherited || self}
                                onChange={(e) => toggle(u, r.key, e.currentTarget.checked)} />
                            </Tooltip>
                          </Table.Td>
                        );
                      })}
                      <Table.Td>
                        {(current.includes("dba") && !current.includes("dba_admin")) ? (
                          <Badge size="sm" color={u.scopes ? "blue" : "orange"} variant="light">{u.scopes ? `${u.scopes} esquema(s)` : "sin alcances"}</Badge>
                        ) : <Text size="xs" c="dimmed">—</Text>}
                      </Table.Td>
                      <Table.Td fz="xs">{fmtDate(u.last_seen)}</Table.Td>
                      <Table.Td>
                        <Button size="compact-xs" disabled={!dirty} loading={saving === u.id} onClick={() => save(u)}>Guardar</Button>
                      </Table.Td>
                    </Table.Tr>
                  );
                })}
              </Table.Tbody>
            </Table>
          </ScrollArea>
          <Text size="xs" c="dimmed" mt="sm">
            Los cambios aplican cuando el usuario renueva su sesión (máximo unos minutos). Un DBA con rol “DBA” necesita alcances de esquemas (pestaña Alcances de DBAs) para poder aprobar.
          </Text>
        </Card>
      )}
    </>
  );
}
