import { Alert, Button, Group, Modal, Stack, Text, TextInput } from "@mantine/core";
import { useQueryClient } from "@tanstack/react-query";
import { IconAlertTriangle } from "@tabler/icons-react";
import { useState } from "react";
import { api, Me } from "../api";
import { notifyError, notifyOk } from "../ui";

/** El DBA activa/desactiva para sí mismo la ejecución desde DBA Manager. Nadie más puede hacerlo por él. */
export default function MyExecutionModal({ opened, onClose, me }: { opened: boolean; onClose: () => void; me: Me }) {
  const qc = useQueryClient();
  const [confirm, setConfirm] = useState("");
  const [busy, setBusy] = useState(false);
  const save = async (enabled: boolean) => {
    setBusy(true);
    try {
      await api.put("/api/me/execution", { enabled, confirm });
      notifyOk(enabled ? "Ejecución desde DBA Manager activada para tu usuario" : "Ejecución desde DBA Manager desactivada");
      qc.invalidateQueries({ queryKey: ["me"] });
      qc.invalidateQueries({ queryKey: ["request"] });
      setConfirm("");
      onClose();
    } catch (e) {
      notifyError(e);
    } finally {
      setBusy(false);
    }
  };
  return (
    <Modal opened={opened} onClose={onClose} title="Ejecución desde DBA Manager">
      <Stack>
        <Text size="sm">
          Estado para tu usuario: <b>{me.can_execute ? "activada" : "desactivada"}</b>.
        </Text>
        <Text size="sm" c="dimmed">
          Con la ejecución activada puedes ejecutar solicitudes aprobadas directamente desde la app con la cuenta privilegiada
          de cada conexión. Desactivada, solo puedes descargar el script, ejecutarlo tú y registrarlo como ejecución manual.
          Por seguridad, únicamente tú puedes activarla para ti; ningún administrador puede activarla por ti. Cada cambio queda en la bitácora.
        </Text>
        {me.can_execute ? (
          <Group justify="flex-end">
            <Button variant="default" onClick={onClose}>Cerrar</Button>
            <Button color="gray" loading={busy} onClick={() => save(false)}>Desactivar</Button>
          </Group>
        ) : (
          <>
            <Alert color="orange" icon={<IconAlertTriangle />} py={6}>
              Las ejecuciones hacen DDL en las bases con commit automático (sin rollback).
            </Alert>
            <TextInput label="Para activarla escribe ACTIVAR" value={confirm} onChange={(e) => setConfirm(e.currentTarget.value)} />
            <Group justify="flex-end">
              <Button variant="default" onClick={onClose}>Cancelar</Button>
              <Button color="red" loading={busy} disabled={confirm.trim().toUpperCase() !== "ACTIVAR"} onClick={() => save(true)}>Activar para mí</Button>
            </Group>
          </>
        )}
      </Stack>
    </Modal>
  );
}
