import { Alert, Autocomplete, Badge, Button, FileButton, Group, Modal, ScrollArea, Stack, Table, Text, Textarea } from "@mantine/core";
import { IconFileImport, IconUpload } from "@tabler/icons-react";
import { useState } from "react";
import { api, Meta, Operation, useSchemas } from "../api";
import { notifyError } from "../ui";

export interface ImportedOp {
  op: Operation;
  notes: string[];
}

interface ImportResult {
  operations: ImportedOp[];
  recognized: number;
  custom: number;
}

const describe = (op: Operation) => {
  const o = op as Record<string, string>;
  return [o.schema, o.table ?? o.object ?? o.synonym ?? o.name ?? o.index_name].filter(Boolean).join(".") +
    (o.grantee ? ` → ${o.grantee}` : "") + (o.type === "CUSTOM_SQL" ? ` — ${o.description}` : "");
};

/** Pega o sube un script SQL y lo convierte en operaciones visuales editables. */
export default function ImportSqlModal({ opened, onClose, connectionId, meta, onImport }: {
  opened: boolean;
  onClose: () => void;
  connectionId: number | null;
  meta: Meta;
  onImport: (ops: ImportedOp[]) => void;
}) {
  const [sql, setSql] = useState("");
  const [schema, setSchema] = useState("");
  const [result, setResult] = useState<ImportResult | null>(null);
  const [busy, setBusy] = useState(false);
  const { data: schemas } = useSchemas(connectionId);
  const label = (t: string) => meta.operations.find((o) => o.type === t)?.label ?? t;

  const analyze = async () => {
    setBusy(true);
    try {
      setResult(await api.post<ImportResult>("/api/requests/import-sql", { connection_id: connectionId, sql, default_schema: schema || null }));
    } catch (e) {
      notifyError(e);
    } finally {
      setBusy(false);
    }
  };
  const close = () => {
    setResult(null);
    onClose();
  };

  return (
    <Modal opened={opened} onClose={close} title="Importar desde script SQL" size="xl">
      <Stack>
        <Text size="sm" c="dimmed">
          Pega el script (CREATE TABLE, GRANT, sinónimos, usuarios, procedimientos, ALTER TABLE…) y se convertirá en operaciones
          visuales que podrás revisar y editar. Lo que no pueda representarse se conserva como SQL personalizado; no se pierde nada.
        </Text>
        <Group align="flex-end">
          <Autocomplete label="Esquema por defecto" description="Para objetos sin esquema en el script (p. ej. CREATE TABLE T1)" w={280}
            data={(schemas ?? []).map((s) => s.name)} value={schema} onChange={(v) => setSchema(v.toUpperCase())} />
          <FileButton accept=".sql,.txt,text/plain" onChange={(f) => f?.text().then((t) => { setSql(t); setResult(null); })}>
            {(props) => <Button {...props} variant="default" leftSection={<IconUpload size={16} />}>Subir archivo .sql</Button>}
          </FileButton>
        </Group>
        <Textarea value={sql} onChange={(e) => { setSql(e.currentTarget.value); setResult(null); }} autosize minRows={8} maxRows={18}
          placeholder={"CREATE TABLE VENTAS.MI_TABLA (\n  ID NUMBER(10) PRIMARY KEY,\n  NOMBRE VARCHAR2(100) NOT NULL\n);\nGRANT SELECT ON VENTAS.MI_TABLA TO BI_LECTOR;\nCREATE SYNONYM BI_LECTOR.MI_TABLA FOR VENTAS.MI_TABLA;"}
          styles={{ input: { fontFamily: "monospace", fontSize: 12.5 } }} />

        {result && (
          <>
            <Alert color={result.custom ? "yellow" : "green"} py={6}>
              {result.recognized} operación(es) convertidas a formulario visual
              {result.custom ? ` · ${result.custom} quedan como SQL personalizado` : ""}
            </Alert>
            <ScrollArea.Autosize mah={300}>
              <Table>
                <Table.Tbody>
                  {result.operations.map((o, i) => (
                    <Table.Tr key={i}>
                      <Table.Td w={30} c="dimmed">{i + 1}</Table.Td>
                      <Table.Td>
                        <Group gap={6}>
                          <Badge size="sm" variant="light" color={o.op.type === "CUSTOM_SQL" ? "yellow" : "blue"}>{label(o.op.type)}</Badge>
                          <Text size="sm" ff="monospace">{describe(o.op)}</Text>
                        </Group>
                        {o.notes.map((n, k) => <Text key={k} size="xs" c="dimmed">• {n}</Text>)}
                      </Table.Td>
                    </Table.Tr>
                  ))}
                </Table.Tbody>
              </Table>
            </ScrollArea.Autosize>
          </>
        )}

        <Group justify="flex-end">
          <Button variant="default" onClick={close}>Cancelar</Button>
          {!result ? (
            <Button onClick={analyze} loading={busy} disabled={!sql.trim() || !connectionId}>Analizar script</Button>
          ) : (
            <Button leftSection={<IconFileImport size={16} />} disabled={!result.operations.length}
              onClick={() => { onImport(result.operations); setSql(""); close(); }}>
              Agregar {result.operations.length} operación(es) a la solicitud
            </Button>
          )}
        </Group>
      </Stack>
    </Modal>
  );
}
