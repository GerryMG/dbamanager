import { ActionIcon, Badge, Box, CopyButton, Tooltip } from "@mantine/core";
import { notifications } from "@mantine/notifications";
import { IconCheck, IconCopy } from "@tabler/icons-react";
import dayjs from "dayjs";
import { ApiError } from "./api";

export const STATUS: Record<string, { label: string; color: string }> = {
  DRAFT: { label: "Borrador", color: "gray" },
  PENDING_APPROVAL: { label: "Pendiente de aprobación", color: "yellow" },
  CHANGES_REQUESTED: { label: "Cambios solicitados", color: "orange" },
  APPROVED: { label: "Aprobada", color: "teal" },
  EXECUTING: { label: "Ejecutando…", color: "blue" },
  REJECTED: { label: "Rechazada", color: "red" },
  EXECUTED: { label: "Ejecutada", color: "green" },
  FAILED: { label: "Falló la ejecución", color: "red" },
  CANCELLED: { label: "Cancelada", color: "dark" },
};

export const StatusBadge = ({ status }: { status: string }) => (
  <Badge color={STATUS[status]?.color ?? "gray"} variant="light">
    {STATUS[status]?.label ?? status}
  </Badge>
);

const ENV_COLOR: Record<string, string> = { PROD: "red", QA: "violet", UAT: "violet", DEV: "blue" };
export const EnvBadge = ({ env }: { env: string }) => (
  <Badge color={ENV_COLOR[env] ?? "gray"} variant="filled" size="sm">
    {env}
  </Badge>
);

export const fmtDate = (d?: string | null) => (d ? dayjs(d).format("DD/MM/YYYY HH:mm") : "—");

export const ACTION_LABELS: Record<string, string> = {
  REQUEST_CREATED: "Creó la solicitud",
  REQUEST_UPDATED: "Editó la solicitud",
  REQUEST_SUBMITTED: "Envió a aprobación",
  REQUEST_APPROVED: "Aprobó",
  REQUEST_REJECTED: "Rechazó",
  REQUEST_CHANGES_REQUESTED: "Solicitó cambios",
  REQUEST_CANCELLED: "Canceló",
  COMMENT_ADDED: "Comentó",
  EXECUTION_STARTED: "Inició ejecución",
  EXECUTION_FINISHED: "Terminó ejecución",
  EXECUTION_MANUAL: "Registró ejecución manual",
  EXECUTION_INTERRUPTED: "Ejecución interrumpida (reinicio del servicio)",
};

export function notifyError(e: unknown) {
  const msg = e instanceof Error ? e.message : String(e);
  const details = e instanceof ApiError && e.details ? e.details.map((d) => `#${d.op_seq} ${d.field}: ${d.message}`).join("\n") : "";
  notifications.show({ color: "red", title: "Error", message: details ? `${msg}\n${details}` : msg, autoClose: 8000, style: { whiteSpace: "pre-line" } });
}

export const notifyOk = (message: string) => notifications.show({ color: "green", message });

export function SqlBlock({ sql, maxHeight = 420 }: { sql: string; maxHeight?: number }) {
  return (
    <Box pos="relative">
      <Box
        component="pre"
        m={0}
        p="sm"
        style={{
          background: "var(--mantine-color-dark-7)",
          color: "var(--mantine-color-gray-2)",
          borderRadius: 6,
          fontSize: 12.5,
          lineHeight: 1.45,
          overflow: "auto",
          maxHeight,
          whiteSpace: "pre",
        }}
      >
        {sql}
      </Box>
      <CopyButton value={sql}>
        {({ copied, copy }) => (
          <Tooltip label={copied ? "Copiado" : "Copiar"}>
            <ActionIcon onClick={copy} variant="subtle" color={copied ? "teal" : "gray"} pos="absolute" top={6} right={6}>
              {copied ? <IconCheck size={16} /> : <IconCopy size={16} />}
            </ActionIcon>
          </Tooltip>
        )}
      </CopyButton>
    </Box>
  );
}
