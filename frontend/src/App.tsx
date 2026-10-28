import { ActionIcon, AppShell, Badge, Burger, Button, Center, Group, Menu, NavLink, Stack, Text, Title, useMantineColorScheme } from "@mantine/core";
import { useDisclosure } from "@mantine/hooks";
import { useQuery } from "@tanstack/react-query";
import {
  IconDatabase, IconFilePlus, IconLock, IconPlayerPlay, IconHistory, IconListCheck, IconLogout, IconMoon, IconSettings, IconSun, IconUserCircle,
} from "@tabler/icons-react";
import { Navigate, Route, Routes, useLocation, useNavigate } from "react-router-dom";
import { api, ApiError, RequestSummary, useMe } from "./api";
import { logout } from "./auth";
import MyExecutionModal from "./components/MyExecutionModal";
import AdminPage from "./pages/AdminPage";
import AuditPage from "./pages/AuditPage";
import ExplorerPage from "./pages/ExplorerPage";
import RequestDetailPage from "./pages/RequestDetailPage";
import RequestEditorPage from "./pages/RequestEditorPage";
import RequestsPage from "./pages/RequestsPage";

export default function App() {
  const [opened, { toggle, close }] = useDisclosure();
  const [execOpen, execModal] = useDisclosure();
  const { data: me, error: meError } = useMe();
  const nav = useNavigate();
  const { pathname } = useLocation();
  const { colorScheme, toggleColorScheme } = useMantineColorScheme();
  const { data: toReview } = useQuery({
    queryKey: ["requests", "to_review"],
    queryFn: () => api.get<RequestSummary[]>("/api/requests?view=to_review"),
    enabled: !!me?.is_dba,
    refetchInterval: 60_000,
  });

  const link = (to: string, label: string, icon: React.ReactNode, extra?: React.ReactNode) => (
    <NavLink
      key={to}
      label={label}
      leftSection={icon}
      rightSection={extra}
      active={
        to === "/" ? pathname === "/"
          : to === "/requests" ? pathname.startsWith(to) && pathname !== "/requests/new"
          : pathname.startsWith(to)
      }
      onClick={() => {
        nav(to);
        close();
      }}
    />
  );

  if (meError instanceof ApiError && meError.status === 403) {
    return (
      <Center h="100vh">
        <Stack align="center" gap="sm" maw={460} ta="center">
          <IconLock size={40} color="var(--mantine-color-gray-6)" />
          <Title order={3}>Sin acceso a DBA Manager</Title>
          <Text c="dimmed">{meError.message}</Text>
          <Button variant="default" onClick={logout}>Cerrar sesión</Button>
        </Stack>
      </Center>
    );
  }

  return (
    <AppShell header={{ height: 56 }} navbar={{ width: 240, breakpoint: "sm", collapsed: { mobile: !opened } }} padding="md">
      <AppShell.Header>
        <Group h="100%" px="md" justify="space-between">
          <Group gap="xs">
            <Burger opened={opened} onClick={toggle} hiddenFrom="sm" size="sm" />
            <IconDatabase size={24} color="var(--mantine-color-blue-6)" />
            <Title order={4}>DBA Manager</Title>
          </Group>
          <Group gap="xs">
            <ActionIcon variant="subtle" onClick={toggleColorScheme} aria-label="Tema">
              {colorScheme === "dark" ? <IconSun size={18} /> : <IconMoon size={18} />}
            </ActionIcon>
            <Menu position="bottom-end">
              <Menu.Target>
                <Group gap={6} style={{ cursor: "pointer" }}>
                  <IconUserCircle size={22} />
                  <Text size="sm" visibleFrom="xs">{me?.full_name || me?.username}</Text>
                </Group>
              </Menu.Target>
              <Menu.Dropdown>
                <Menu.Label>{me?.username}</Menu.Label>
                <Menu.Item disabled>
                  {me?.roles.filter((r) => ["solicitante", "dba", "dba_admin", "admin"].includes(r)).join(", ")}
                </Menu.Item>
                {me?.is_dba && (
                  <Menu.Item leftSection={<IconPlayerPlay size={16} />} onClick={execModal.open}
                    rightSection={<Badge size="xs" color={me.can_execute ? "red" : "gray"} variant="light">{me.can_execute ? "activada" : "desactivada"}</Badge>}>
                    Ejecución desde la app
                  </Menu.Item>
                )}
                <Menu.Divider />
                <Menu.Item leftSection={<IconLogout size={16} />} onClick={logout}>
                  Cerrar sesión
                </Menu.Item>
              </Menu.Dropdown>
            </Menu>
          </Group>
        </Group>
      </AppShell.Header>

      <AppShell.Navbar p="xs">
        {link("/", "Explorar estructura", <IconDatabase size={18} />)}
        {link("/requests/new", "Nueva solicitud", <IconFilePlus size={18} />)}
        {link(
          "/requests",
          "Solicitudes",
          <IconListCheck size={18} />,
          toReview && toReview.length > 0 ? <Badge size="sm" color="yellow" circle>{toReview.length}</Badge> : null,
        )}
        {me?.is_dba && link("/audit", "Bitácora", <IconHistory size={18} />)}
        {me?.is_dba && link("/admin", "Administración", <IconSettings size={18} />)}
      </AppShell.Navbar>

      {me && <MyExecutionModal opened={execOpen} onClose={execModal.close} me={me} />}
      <AppShell.Main>
        <Routes>
          <Route path="/" element={<ExplorerPage />} />
          <Route path="/requests" element={<RequestsPage />} />
          <Route path="/requests/new" element={<RequestEditorPage />} />
          <Route path="/requests/:id" element={<RequestDetailPage />} />
          <Route path="/requests/:id/edit" element={<RequestEditorPage />} />
          <Route path="/audit" element={<AuditPage />} />
          <Route path="/admin" element={<AdminPage />} />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </AppShell.Main>
    </AppShell>
  );
}
