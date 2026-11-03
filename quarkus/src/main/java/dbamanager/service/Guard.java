package dbamanager.service;

import dbamanager.core.ApiException;
import dbamanager.core.Crypto;
import dbamanager.core.Json;
import dbamanager.core.Settings;
import dbamanager.model.ChangeRequest;
import dbamanager.model.DbConnection;
import dbamanager.model.RequestOperation;
import dbamanager.ops.Ops.Grant;
import dbamanager.ops.Ops.Op;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Reglas de seguridad de las solicitudes: esquemas protegidos, operaciones de alto riesgo y la
 * huella exacta de lo aprobado.
 */
@ApplicationScoped
public class Guard {
    /** Esquemas internos de Oracle: nunca pueden ser objeto de una solicitud. */
    public static final Set<String> ORACLE_INTERNAL = Set.of(
            "SYS", "SYSTEM", "SYSBACKUP", "SYSDG", "SYSKM", "SYSRAC", "SYS$UMF", "AUDSYS", "OUTLN", "DBSNMP", "APPQOSSYS",
            "GSMADMIN_INTERNAL", "GSMCATUSER", "GSMUSER", "GSMROOTUSER", "GGSYS", "DIP", "REMOTE_SCHEDULER_AGENT",
            "ANONYMOUS", "XS$NULL", "XDB", "CTXSYS", "MDSYS", "MDDATA", "ORDSYS", "ORDDATA", "ORDPLUGINS",
            "SI_INFORMTN_SCHEMA", "OLAPSYS", "LBACSYS", "DVSYS", "DVF", "WMSYS", "OJVMSYS", "ORACLE_OCM", "DGPDB_INT",
            "DBSFWUSER", "PDBADMIN", "VECSYS", "BAASSYS", "GGSHAREDCAP", "FLOWS_FILES", "APEX_PUBLIC_USER");
    /**
     * Operaciones cuyo alcance real no se puede verificar (código o SQL libre) o que afectan a todos:
     * solo un DBA principal (dba_admin) puede aprobarlas y ejecutarlas.
     */
    public static final Set<String> HIGH_RISK = Set.of("CUSTOM_SQL", "CREATE_OR_REPLACE_CODE", "CREATE_USER");

    @Inject Settings settings;
    @Inject Crypto crypto;
    @Inject Connections connections;

    public Set<String> protectedSchemas(DbConnection c) {
        Set<String> s = new TreeSet<>(ORACLE_INTERNAL);
        s.addAll(connections.serviceAccounts(c));
        s.addAll(settings.protectedSchemas());
        return s;
    }

    /** Rechaza operaciones sobre esquemas internos de Oracle, cuentas de servicio o la metadata. */
    public void checkProtected(DbConnection c, List<Op> parsed) {
        Set<String> blocked = protectedSchemas(c);
        List<Map<String, Object>> errors = new ArrayList<>();
        for (int i = 0; i < parsed.size(); i++) {
            Op op = parsed.get(i);
            Set<String> hit = new TreeSet<>(op.schemas());
            hit.retainAll(blocked);
            if (op instanceof Grant g && g.grantee != null && blocked.contains(g.grantee.toUpperCase())) hit.add(g.grantee.toUpperCase());
            if (!hit.isEmpty()) {
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("op_seq", i + 1);
                e.put("field", "schema");
                e.put("message", "Esquema protegido: " + String.join(", ", hit) + ". DBA Manager no permite solicitudes sobre esquemas "
                        + "internos de Oracle, sus cuentas de servicio ni la metadata");
                errors.add(e);
            }
        }
        if (!errors.isEmpty()) throw ApiException.invalid("Operaciones sobre esquemas protegidos", errors);
    }

    public static boolean requiresPrincipal(ChangeRequest r) {
        return r.operations.stream().anyMatch(o -> HIGH_RISK.contains(o.opType) || "PUBLIC".equals(o.targetSchema));
    }

    /** Lo que se aprueba: base destino y SQL exacto, en JSON canónico. */
    public static String content(ChangeRequest r) {
        DbConnection c = r.connection;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("connection", List.of(c.id, c.engine, c.host, c.port, c.serviceName, String.valueOf(c.execUser), c.useTls));
        List<Object> ops = new ArrayList<>();
        for (RequestOperation o : r.operations) ops.add(List.of(o.seq, o.opType, Json.list(o.statements)));
        payload.put("operations", ops);
        return Json.canonical(payload);
    }

    /** Huella SHA-256 de lo aprobado (la firma el DBA con su llave; no depende de ningún secreto del servidor). */
    public static String digest(ChangeRequest r) {
        return Crypto.sha256Hex(content(r));
    }

    /** Sello HMAC con SECRETS_KEY de lo aprobado. */
    public String fingerprint(ChangeRequest r) {
        return crypto.seal(content(r));
    }
}
