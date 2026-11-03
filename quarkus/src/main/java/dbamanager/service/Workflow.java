package dbamanager.service;

import dbamanager.core.ApiException;
import dbamanager.core.Crypto;
import dbamanager.core.CurrentUser;
import dbamanager.core.Json;
import dbamanager.core.Settings;
import dbamanager.engine.ConnInfo;
import dbamanager.engine.OracleDdl;
import dbamanager.engine.OracleEngine;
import dbamanager.engine.Stmt;
import dbamanager.model.*;
import dbamanager.ops.OpParser;
import dbamanager.ops.Ops.Op;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Ciclo de vida de una solicitud de cambio.
 *
 * <pre>
 *   DRAFT ──submit──▶ PENDING_APPROVAL ──approve(n)──▶ APPROVED ──execute──▶ EXECUTED
 *     ▲                    │   │                          │
 *     └─ CHANGES_REQUESTED ◀┘   └──reject──▶ REJECTED      └──falla──▶ FAILED ──reintentar──▶ ...
 *   (cancelar posible antes de ejecutar)
 * </pre>
 *
 * Reglas: quien solicita nunca aprueba lo suyo; dba_admin aprueba todo y dba solo con alcance sobre
 * todos los esquemas afectados; lo aprobado es exactamente lo que se ejecuta; cada decisión y cada
 * ejecución lleva la firma de la llave de seguridad del DBA, y se vuelve a verificar antes de ejecutar.
 */
@ApplicationScoped
public class Workflow {
    public static final Set<String> EDITABLE = Set.of("DRAFT", "CHANGES_REQUESTED");
    public static final Set<String> CANCELLABLE = Set.of("DRAFT", "PENDING_APPROVAL", "CHANGES_REQUESTED", "APPROVED");
    public static final Set<String> EXECUTABLE = Set.of("APPROVED", "FAILED");

    @Inject Settings settings;
    @Inject Guard guard;
    @Inject Connections connections;
    @Inject Keys keys;

    // ---------------------------------------------------------------- operaciones / SQL
    public List<Op> parse(List<Map<String, Object>> raw) {
        return OpParser.parse(raw, settings.maxOperations);
    }

    public List<List<Stmt>> render(DbConnection c, List<Op> parsed) {
        guard.checkProtected(c, parsed);
        int[] version = OracleDdl.parseVersion(connections.serverVersion(c));
        List<List<Stmt>> out = new ArrayList<>();
        List<Map<String, Object>> errors = new ArrayList<>();
        for (int i = 0; i < parsed.size(); i++) {
            try {
                out.add(OracleDdl.render(parsed.get(i), version));
            } catch (IllegalArgumentException e) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("op_seq", i + 1);
                m.put("field", "");
                m.put("message", e.getMessage());
                errors.add(m);
            }
        }
        if (!errors.isEmpty()) throw ApiException.invalid("No se pudo generar el SQL", errors);
        return out;
    }

    public static List<Stmt> statementsOf(RequestOperation op) {
        return Json.list(op.statements).stream().map(Stmt::of).toList();
    }

    public String buildScript(ChangeRequest r) {
        List<String> header = new ArrayList<>(List.of(
                "-- " + r.code() + ": " + r.title,
                "-- Conexión: " + r.connection.name + " (" + r.connection.environment + ") " + r.connection.host + "/" + r.connection.serviceName,
                "-- Proyecto: " + (r.project != null ? r.project.code + " - " + r.project.name : "(sin proyecto)"),
                "-- Solicitó: " + r.requestedBy + "   Estado: " + r.status));
        List<String> approvers = r.approvals.stream().filter(a -> a.active && "APPROVED".equals(a.decision)).map(a -> a.approver).toList();
        if (!approvers.isEmpty()) header.add("-- Aprobó: " + String.join(", ", approvers));
        header.add("-- Huella de lo aprobado (SHA-256): " + Guard.digest(r));
        String reason = String.join(" ", r.reason.split("\\s+"));
        header.add("-- Motivo: " + (reason.length() > 500 ? reason.substring(0, 500) : reason));
        List<String> body = new ArrayList<>();
        for (RequestOperation op : r.operations) {
            body.add("-- [" + op.seq + "] " + op.opType + " " + op.targetSchema + "." + Objects.toString(op.objectName, "") + "  (" + op.status + ")");
            if ("CREATE_USER".equals(op.opType)) body.add("-- Reemplaza {{PASSWORD}} por una contraseña segura antes de ejecutar");
            body.add(OracleDdl.script(statementsOf(op)));
        }
        return String.join("\n", header) + "\n\n" + String.join("\n\n", body) + "\n";
    }

    private void storeOperations(ChangeRequest r, List<Map<String, Object>> raw) {
        List<Op> parsed = parse(raw);
        List<List<Stmt>> rendered = render(r.connection, parsed);
        for (RequestOperation old : r.operations) old.delete();
        r.operations.clear();
        RequestOperation.getEntityManager().flush();
        Set<String> schemas = new TreeSet<>();
        for (int i = 0; i < parsed.size(); i++) {
            Op op = parsed.get(i);
            RequestOperation o = new RequestOperation();
            o.request = r;
            o.seq = i + 1;
            o.opType = op.type;
            o.targetSchema = op.schema;
            o.objectName = op.objectName();
            o.payload = OpParser.toJson(op);
            o.statements = Json.write(rendered.get(i));
            o.status = "PENDING";
            r.operations.add(o);
            schemas.addAll(op.schemas());
        }
        // Alcance = todo esquema que se modifica o referencia (no solo el principal de cada operación)
        r.targetSchemas = String.join(",", schemas);
    }

    // ---------------------------------------------------------------- permisos
    public static Set<String> schemasOf(ChangeRequest r) {
        Set<String> s = new TreeSet<>();
        for (String x : Objects.toString(r.targetSchemas, "").split(",")) if (!x.isBlank()) s.add(x);
        return s;
    }

    /** Alcances vigentes del usuario en la conexión: solo cuentan los firmados por un DBA principal. */
    public Set<String> validScopes(String username, Long connectionId) {
        Set<String> out = new TreeSet<>();
        for (ApproverScope s : ApproverScope.<ApproverScope>list("username = ?1 and connectionId = ?2", username, connectionId)) {
            if (scopeValid(s)) out.add(s.schemaName.toUpperCase());
        }
        return out;
    }

    public boolean scopeValid(ApproverScope s) {
        return s.key != null && keys.validSignature(s.key, s.signature, s.grantedBy, Map.of(
                "kind", "scope", "username", s.username, "connection_id", s.connectionId, "schema", s.schemaName));
    }

    public boolean hasScope(CurrentUser user, Long connectionId, Set<String> schemas) {
        if (user.isDbaAdmin()) return true;
        if (!user.isDba()) return false;
        Set<String> allowed = validScopes(user.username(), connectionId);
        return allowed.contains("*") || allowed.containsAll(schemas);
    }

    private boolean authorized(CurrentUser user, ChangeRequest r) {
        if (Guard.requiresPrincipal(r) && !user.isDbaAdmin()) return false;
        return hasScope(user, r.connection.id, schemasOf(r));
    }

    public boolean canReview(CurrentUser user, ChangeRequest r) {
        return !user.username().equals(r.requestedBy) && authorized(user, r);
    }

    public boolean canExecute(CurrentUser user, ChangeRequest r) {
        return authorized(user, r);
    }

    /** null = desactivada; OffsetDateTime.MAX = sin vencimiento. */
    public OffsetDateTime selfExecutionUntil(String username) {
        UserSetting row = UserSetting.findById(username);
        if (row == null || !row.canExecute) return null;
        if (row.canExecuteUntil == null) return OffsetDateTime.MAX;
        return row.canExecuteUntil.isAfter(OffsetDateTime.now()) ? row.canExecuteUntil : null;
    }

    public boolean selfExecutionEnabled(String username) {
        return selfExecutionUntil(username) != null;
    }

    /** Solo modifica la preferencia del propio usuario: no existe forma de cambiarla para otra persona. */
    public OffsetDateTime setSelfExecution(CurrentUser user, boolean enabled, String token, Map<String, Object> assertion) {
        Keys.Signed signed = null;
        if (enabled) {
            if (!user.isDba()) throw ApiException.of(403, "Solo un DBA puede activar la ejecución desde DBA Manager");
            signed = keys.sign(user, "self_execution", token, assertion);
        }
        UserSetting row = UserSetting.findById(user.username());
        if (row == null) {
            row = new UserSetting();
            row.username = user.username();
            row.persist();
        }
        row.canExecute = enabled;
        row.canExecuteChangedAt = OffsetDateTime.now();
        row.canExecuteUntil = enabled && settings.selfExecutionHours > 0 ? OffsetDateTime.now().plusHours(settings.selfExecutionHours) : null;
        Audit.log(user, enabled ? "SELF_EXECUTION_ENABLED" : "SELF_EXECUTION_DISABLED", "user", user.username(),
                "until", row.canExecuteUntil, "key", signed == null ? null : Keys.shortFingerprint(signed.key().fingerprint));
        return row.canExecuteUntil;
    }

    public Map<String, Object> permissions(CurrentUser user, ChangeRequest r) {
        boolean owner = user.username().equals(r.requestedBy);
        boolean already = r.approvals.stream().anyMatch(a -> a.active && a.approver.equals(user.username()));
        boolean hasKey = user.isDba() && keys.hasTrustedKey(user.username());
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("edit", owner && EDITABLE.contains(r.status));
        p.put("submit", owner && EDITABLE.contains(r.status));
        p.put("cancel", (owner || user.isDbaAdmin()) && CANCELLABLE.contains(r.status));
        p.put("review", canReview(user, r) && "PENDING_APPROVAL".equals(r.status) && !already);
        p.put("execute", canExecute(user, r) && EXECUTABLE.contains(r.status));
        p.put("auto_execute", r.connection.allowExecution && r.connection.execUser != null);
        p.put("self_execution", selfExecutionEnabled(user.username()));
        p.put("has_key", hasKey);
        p.put("comment", true);
        return p;
    }

    // ---------------------------------------------------------------- acciones
    public ChangeRequest create(CurrentUser user, Map<String, Object> data, DbConnection c, Project project) {
        ChangeRequest r = new ChangeRequest();
        applyFields(r, data, c, project);
        r.status = "DRAFT";
        r.requestedBy = user.username();
        r.persist();
        storeOperations(r, operationsOf(data));
        Audit.log(user, "REQUEST_CREATED", "request", r.id, "title", r.title, "connection", c.name,
                "operations", r.operations.stream().map(o -> o.opType).toList());
        if (Boolean.TRUE.equals(data.get("submit"))) submit(user, r);
        return r;
    }

    public void update(CurrentUser user, ChangeRequest r, Map<String, Object> data, DbConnection c, Project project) {
        if (!user.username().equals(r.requestedBy) || !EDITABLE.contains(r.status))
            throw ApiException.of(409, "Solo quien solicita puede editar, y solo en borrador o con cambios solicitados");
        applyFields(r, data, c, project);
        storeOperations(r, operationsOf(data));
        for (Approval a : r.approvals) a.active = false;
        Audit.log(user, "REQUEST_UPDATED", "request", r.id, "operations", r.operations.stream().map(o -> o.opType).toList());
        if (Boolean.TRUE.equals(data.get("submit"))) submit(user, r);
    }

    private static void applyFields(ChangeRequest r, Map<String, Object> data, DbConnection c, Project project) {
        String title = str(data.get("title")), reason = str(data.get("reason"));
        if (title == null || title.length() < 5 || title.length() > 200) throw ApiException.of(422, "El título debe tener entre 5 y 200 caracteres");
        if (reason == null || reason.length() < 10 || reason.length() > 4000) throw ApiException.of(422, "El motivo debe tener entre 10 y 4000 caracteres");
        String purpose = str(data.get("purpose")), ticket = str(data.get("ticket_ref"));
        if (purpose != null && purpose.length() > 4000) throw ApiException.of(422, "El propósito debe tener máximo 4000 caracteres");
        if (ticket != null && ticket.length() > 100) throw ApiException.of(422, "La referencia debe tener máximo 100 caracteres");
        r.title = title;
        r.reason = reason;
        r.purpose = purpose;
        r.ticketRef = ticket;
        r.connection = c;
        r.project = project;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> operationsOf(Map<String, Object> data) {
        Object ops = data.get("operations");
        if (!(ops instanceof List<?> l) || l.isEmpty()) throw ApiException.of(422, "Agrega al menos una operación");
        for (Object o : l) if (!(o instanceof Map)) throw ApiException.of(422, "Operación inválida");
        return (List<Map<String, Object>>) ops;
    }

    static String str(Object o) {
        if (o == null) return null;
        String s = o.toString().strip();
        return s.isEmpty() ? null : s;
    }

    public void submit(CurrentUser user, ChangeRequest r) {
        if (!user.username().equals(r.requestedBy) || !EDITABLE.contains(r.status))
            throw ApiException.of(409, "No se puede enviar una solicitud en estado " + r.status);
        if (r.operations.isEmpty()) throw ApiException.of(422, "La solicitud no tiene operaciones");
        for (Approval a : r.approvals) a.active = false;
        r.status = "PENDING_APPROVAL";
        r.submittedAt = OffsetDateTime.now();
        Audit.log(user, "REQUEST_SUBMITTED", "request", r.id);
    }

    /** Lo que firma el DBA al decidir. */
    public Map<String, Object> reviewStatement(CurrentUser user, ChangeRequest r, String decision, String comment) {
        Map<String, Object> st = new LinkedHashMap<>();
        st.put("request", r.id);
        st.put("code", r.code());
        st.put("title", r.title);
        st.put("decision", decision);
        st.put("approver", user.username());
        st.put("digest", Guard.digest(r));
        st.put("comment_sha256", Crypto.sha256Hex(Objects.toString(comment, "")));
        return st;
    }

    public void checkCanReview(CurrentUser user, ChangeRequest r, String decision, String comment) {
        if (!"PENDING_APPROVAL".equals(r.status)) throw ApiException.of(409, "La solicitud está en estado " + r.status);
        if (user.username().equals(r.requestedBy)) throw ApiException.of(403, "No puedes revisar tu propia solicitud");
        if (!Set.of("APPROVED", "REJECTED", "CHANGES_REQUESTED").contains(decision)) throw ApiException.of(422, "Decisión inválida");
        if (Guard.requiresPrincipal(r) && !user.isDbaAdmin())
            throw ApiException.of(403, "Esta solicitud contiene SQL libre, código PL/SQL, usuarios o sinónimos públicos: "
                    + "solo un DBA principal (dba_admin) puede aprobarla");
        if (!canReview(user, r)) throw ApiException.of(403, "No tienes alcance de aprobación sobre los esquemas: " + String.join(", ", schemasOf(r)));
        if (!"APPROVED".equals(decision) && (comment == null || comment.isBlank()))
            throw ApiException.of(422, "Rechazar o pedir cambios requiere un comentario");
        if (comment != null && comment.length() > 4000) throw ApiException.of(422, "El comentario debe tener máximo 4000 caracteres");
        if (r.approvals.stream().anyMatch(a -> a.active && a.approver.equals(user.username())))
            throw ApiException.of(409, "Ya registraste tu decisión en esta solicitud");
        if (!keys.hasTrustedKey(user.username()))
            throw ApiException.coded(403, "KEY_REQUIRED", "Para decidir necesitas tu llave de seguridad registrada y avalada (menú Mi llave)");
    }

    public void review(CurrentUser user, ChangeRequest r, String decision, String comment, String token, Map<String, Object> assertion) {
        checkCanReview(user, r, decision, comment);
        Keys.Signed signed = keys.sign(user, "approval", token, assertion);
        Map<String, Object> st = Keys.statementOf(signed.json());
        Map<String, Object> expected = reviewStatement(user, r, decision, comment);
        for (String k : List.of("request", "decision", "approver", "digest", "comment_sha256")) {
            if (!Objects.equals(String.valueOf(st.get(k)), String.valueOf(expected.get(k))))
                throw ApiException.of(409, "La solicitud cambió mientras firmabas; revisa y vuelve a intentarlo");
        }
        Approval a = new Approval();
        a.request = r;
        a.approver = user.username();
        a.decision = decision;
        a.comment = comment;
        a.active = true;
        a.contentHash = "APPROVED".equals(decision) ? guard.fingerprint(r) : null;
        a.key = signed.key();
        a.signature = signed.json();
        r.approvals.add(a);
        switch (decision) {
            case "REJECTED" -> {
                r.status = "REJECTED";
                r.decidedAt = OffsetDateTime.now();
            }
            case "CHANGES_REQUESTED" -> r.status = "CHANGES_REQUESTED";
            default -> {
                long approved = r.approvals.stream().filter(x -> x.active && "APPROVED".equals(x.decision)).map(x -> x.approver).distinct().count();
                if (approved >= Math.max(1, r.connection.requiredApprovals)) {
                    r.status = "APPROVED";
                    r.decidedAt = OffsetDateTime.now();
                }
            }
        }
        Audit.log(user, "REQUEST_" + decision, "request", r.id, "comment", comment, "new_status", r.status,
                "key", Keys.shortFingerprint(signed.key().fingerprint));
    }

    public void cancel(CurrentUser user, ChangeRequest r, String comment) {
        if (!((user.username().equals(r.requestedBy) || user.isDbaAdmin()) && CANCELLABLE.contains(r.status)))
            throw ApiException.of(409, "No se puede cancelar en estado " + r.status);
        r.status = "CANCELLED";
        Audit.log(user, "REQUEST_CANCELLED", "request", r.id, "comment", comment);
    }

    public void comment(CurrentUser user, ChangeRequest r, String body) {
        if (body == null || body.isBlank() || body.length() > 4000) throw ApiException.of(422, "El comentario debe tener entre 1 y 4000 caracteres");
        RequestComment c = new RequestComment();
        c.request = r;
        c.author = user.username();
        c.body = body;
        r.comments.add(c);
        Audit.log(user, "COMMENT_ADDED", "request", r.id);
    }

    // ---------------------------------------------------------------- ejecución
    public Map<String, Object> executionStatement(CurrentUser user, ChangeRequest r, String mode, String notes) {
        Map<String, Object> st = new LinkedHashMap<>();
        st.put("request", r.id);
        st.put("code", r.code());
        st.put("executor", user.username());
        st.put("mode", mode);
        st.put("digest", Guard.digest(r));
        st.put("notes_sha256", Crypto.sha256Hex(Objects.toString(notes, "")));
        return st;
    }

    void checkExecutable(CurrentUser user, ChangeRequest r, String confirmCode) {
        if (!EXECUTABLE.contains(r.status)) throw ApiException.of(409, "Solo se ejecutan solicitudes aprobadas (estado actual: " + r.status + ")");
        if (!canExecute(user, r))
            throw ApiException.of(403, "No tienes alcance para ejecutar esta solicitud" + (Guard.requiresPrincipal(r) ? " (requiere DBA principal)" : ""));
        if (confirmCode == null || !confirmCode.strip().equalsIgnoreCase(r.code()))
            throw ApiException.of(422, "Para confirmar escribe el código exacto de la solicitud (" + r.code() + ")");
    }

    /**
     * Lo que se va a ejecutar debe ser exactamente lo aprobado, contra la misma base, y cada aprobación
     * debe llevar una firma válida de la llave confiable de quien aprobó. Si algo no cuadra, la
     * solicitud vuelve a "Pendiente de aprobación". Devuelve null si todo es válido o el motivo.
     */
    public String integrityProblem(ChangeRequest r) {
        String seal = guard.fingerprint(r);
        String digest = Guard.digest(r);
        List<Approval> approvals = r.approvals.stream().filter(a -> a.active && "APPROVED".equals(a.decision)).toList();
        Set<String> valid = new TreeSet<>();
        for (Approval a : approvals) {
            boolean sealOk = a.contentHash != null && Crypto.sameHex(a.contentHash, seal);
            boolean sigOk = keys.validSignature(a.key, a.signature, a.approver, Map.of(
                    "kind", "approval", "request", r.id, "decision", "APPROVED", "approver", a.approver, "digest", digest));
            if (!sealOk) return "El SQL almacenado o la conexión destino cambiaron después de la aprobación";
            if (!sigOk) return "La aprobación de " + a.approver + " no tiene una firma válida de su llave de seguridad";
            valid.add(a.approver);
        }
        if (valid.size() < Math.max(1, r.connection.requiredApprovals)) return "Faltan aprobaciones firmadas";
        return null;
    }

    /** Marca la solicitud como pendiente otra vez (en su propia transacción) y lanza 409. */
    private void integrityFailed(CurrentUser user, Long requestId, String reason) {
        QuarkusTransaction.requiringNew().run(() -> {
            ChangeRequest r = ChangeRequest.findById(requestId);
            for (Approval a : r.approvals) a.active = false;
            r.status = "PENDING_APPROVAL";
            Audit.log(user, "INTEGRITY_MISMATCH", "request", r.id, "detail", reason);
        });
        throw ApiException.of(409, reason + ": la solicitud volvió a 'Pendiente de aprobación' y debe aprobarse de nuevo");
    }

    /**
     * Ejecuta con la cuenta privilegiada, estrictamente en el orden de las operaciones: al primer
     * error se detiene todo; un reintento continúa en la sentencia exacta donde se quedó; la solicitud
     * se bloquea en EXECUTING para que dos DBAs no la ejecuten a la vez.
     */
    public Map<String, Object> execute(CurrentUser user, Long requestId, String confirmCode, String token, Map<String, Object> assertion) {
        record Prep(ConnInfo info, String code, String previous, Long keyId, String signature) {}
        Prep prep = QuarkusTransaction.requiringNew().call(() -> {
            ChangeRequest r = ChangeRequest.findById(requestId);
            if (r == null) throw ApiException.of(404, "Solicitud no encontrada");
            checkExecutable(user, r, confirmCode);
            DbConnection c = r.connection;
            if (!c.active) throw ApiException.of(409, "La conexión está desactivada; no se puede ejecutar");
            if (!c.allowExecution)
                throw ApiException.of(409, "Esta conexión no permite ejecución desde DBA Manager; usa el script y marca como ejecutada");
            if (!selfExecutionEnabled(user.username()))
                throw ApiException.of(403, "No tienes activada (o ya venció) la ejecución desde DBA Manager. Solo tú puedes "
                        + "activarla, con tu llave; mientras tanto usa el script y marca la solicitud como ejecutada");
            Keys.Signed signed = signExecution(user, r, "AUTO", null, token, assertion);
            return new Prep(connections.info(c, "exec", user.username()), r.code(), r.status, signed.key().id, signed.json());
        });
        String problem = QuarkusTransaction.requiringNew().call(() -> integrityProblem(ChangeRequest.findById(requestId)));
        if (problem != null) integrityFailed(user, requestId, problem);
        // Candado final contra el diccionario (además de la lista fija): nunca se ejecuta sobre esquemas internos
        Set<String> internal;
        try {
            Set<String> schemas = QuarkusTransaction.requiringNew().call(() -> schemasOf(ChangeRequest.findById(requestId)));
            schemas.remove("PUBLIC");
            ConnInfo catalog = QuarkusTransaction.requiringNew().call(() -> connections.info(ChangeRequest.<ChangeRequest>findById(requestId).connection, "catalog", user.username()));
            internal = OracleEngine.oracleMaintained(catalog, schemas);
        } catch (java.sql.SQLException e) {
            throw ApiException.of(502, "No se pudo verificar el destino: " + e.getMessage());
        }
        if (!internal.isEmpty())
            throw ApiException.of(403, "La solicitud afecta esquemas internos de Oracle (" + String.join(", ", internal) + "); no se ejecuta");

        int locked = QuarkusTransaction.requiringNew().call(() -> {
            int n = ChangeRequest.update("status = 'EXECUTING' where id = ?1 and status in ?2", requestId, EXECUTABLE);
            Audit.log(user, "EXECUTION_STARTED", "request", requestId);
            return n;
        });
        if (locked == 0) throw ApiException.of(409, "La solicitud ya se está ejecutando o cambió de estado");

        List<String> log = new ArrayList<>();
        List<Map<String, Object>> secrets = new ArrayList<>();
        boolean ok = true;
        OffsetDateTime started = OffsetDateTime.now();
        try {
            List<Long> opIds = QuarkusTransaction.requiringNew().call(() ->
                    ChangeRequest.<ChangeRequest>findById(requestId).operations.stream().map(o -> o.id).toList());
            for (Long opId : opIds) {
                RequestOperation op = QuarkusTransaction.requiringNew().call(() -> RequestOperation.<RequestOperation>findById(opId));
                if ("EXECUTED".equals(op.status)) continue;
                List<Stmt> all = statementsOf(op);
                List<Stmt> pending = all.subList(op.statementsDone, all.size());
                if (op.statementsDone > 0) log.add("[" + op.seq + "] reanudando en la sentencia " + (op.statementsDone + 1));
                List<OracleEngine.Result> results = OracleEngine.execute(prep.info(), pending, prep.code());
                for (OracleEngine.Result res : results) {
                    String first = res.sql().lines().findFirst().orElse("");
                    log.add("[" + op.seq + "] " + (res.ok() ? "OK   " : "ERROR") + " " + (first.length() > 150 ? first.substring(0, 150) : first));
                    if (!res.ok()) log.add("      " + res.message());
                    if (res.secret() != null) {
                        Map<String, Object> s = new LinkedHashMap<>();
                        s.put("operation", op.seq);
                        s.put("user", op.targetSchema);
                        s.put("password", res.secret());
                        secrets.add(s);
                    }
                }
                long done = results.stream().filter(OracleEngine.Result::ok).count();
                boolean opOk = !results.isEmpty() && done == pending.size();
                String failMsg = results.stream().filter(x -> !x.ok()).map(OracleEngine.Result::message).findFirst().orElse("Sin resultado");
                QuarkusTransaction.requiringNew().run(() -> {  // avance persistido operación por operación
                    RequestOperation o = RequestOperation.findById(opId);
                    o.statementsDone += (int) done;
                    if (opOk) {
                        o.status = "EXECUTED";
                        o.message = "OK";
                        o.executedAt = OffsetDateTime.now();
                    } else {
                        o.status = "FAILED";
                        o.message = failMsg;
                    }
                });
                if (!opOk) {
                    ok = false;
                    break;
                }
            }
        } catch (Exception e) {  // error de conexión u otro: se registra y la solicitud queda FAILED
            ok = false;
            log.add("      Error inesperado: " + e.getMessage());
        } finally {
            boolean success = ok;
            QuarkusTransaction.requiringNew().run(() -> {
                ChangeRequest r = ChangeRequest.findById(requestId);
                r.status = success ? "EXECUTED" : "FAILED";
                if (success) r.executedAt = OffsetDateTime.now();
                Execution ex = new Execution();
                ex.request = r;
                ex.executedBy = user.username();
                ex.mode = "AUTO";
                ex.status = success ? "SUCCESS" : "FAILED";
                ex.log = String.join("\n", log);
                ex.startedAt = started;
                ex.finishedAt = OffsetDateTime.now();
                ex.key = DbaKey.findById(prep.keyId());
                ex.signature = prep.signature();
                r.executions.add(ex);
                // Las contraseñas generadas NO se guardan: solo se registra que se generaron.
                Audit.log(user, "EXECUTION_FINISHED", "request", r.id, "status", r.status, "previous_status", prep.previous(),
                        "generated_passwords", secrets.stream().map(s -> s.get("user")).toList());
            });
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", ok ? "EXECUTED" : "FAILED");
        out.put("log", String.join("\n", log));
        out.put("secrets", secrets);
        return out;
    }

    private Keys.Signed signExecution(CurrentUser user, ChangeRequest r, String mode, String notes, String token, Map<String, Object> assertion) {
        Keys.Signed signed = keys.sign(user, "execution", token, assertion);
        Map<String, Object> st = Keys.statementOf(signed.json());
        Map<String, Object> expected = executionStatement(user, r, mode, notes);
        for (String k : List.of("request", "executor", "mode", "digest", "notes_sha256")) {
            if (!Objects.equals(String.valueOf(st.get(k)), String.valueOf(expected.get(k))))
                throw ApiException.of(409, "La solicitud cambió mientras firmabas; revisa y vuelve a intentarlo");
        }
        return signed;
    }

    /** Al arrancar: una solicitud en EXECUTING significa que el proceso se interrumpió a mitad. */
    public int recoverInterrupted() {
        List<ChangeRequest> rows = ChangeRequest.list("status", "EXECUTING");
        for (ChangeRequest r : rows) {
            r.status = "FAILED";
            Audit.system("EXECUTION_INTERRUPTED", "request", r.id, "note", "El servicio se reinició durante la ejecución; revisa el avance y reintenta");
        }
        return rows.size();
    }

    /** El DBA ejecutó el script fuera de la app y registra el resultado. */
    public void markExecuted(CurrentUser user, Long requestId, String confirmCode, String notes, String token, Map<String, Object> assertion) {
        Keys.Signed signed = QuarkusTransaction.requiringNew().call(() -> {
            ChangeRequest r = ChangeRequest.findById(requestId);
            if (r == null) throw ApiException.of(404, "Solicitud no encontrada");
            checkExecutable(user, r, confirmCode);
            if (notes == null || notes.isBlank()) throw ApiException.of(422, "Describe cómo/dónde se ejecutó (notas obligatorias)");
            if (notes.length() > 4000) throw ApiException.of(422, "Las notas deben tener máximo 4000 caracteres");
            return signExecution(user, r, "MANUAL", notes, token, assertion);
        });
        String problem = QuarkusTransaction.requiringNew().call(() -> integrityProblem(ChangeRequest.findById(requestId)));
        if (problem != null) integrityFailed(user, requestId, problem);
        QuarkusTransaction.requiringNew().run(() -> {
            ChangeRequest r = ChangeRequest.findById(requestId);
            for (RequestOperation op : r.operations) {
                if (!"EXECUTED".equals(op.status)) {
                    op.status = "EXECUTED";
                    op.message = "Ejecutado manualmente";
                    op.executedAt = OffsetDateTime.now();
                }
            }
            r.status = "EXECUTED";
            r.executedAt = OffsetDateTime.now();
            Execution ex = new Execution();
            ex.request = r;
            ex.executedBy = user.username();
            ex.mode = "MANUAL";
            ex.status = "SUCCESS";
            ex.notes = notes;
            ex.startedAt = OffsetDateTime.now();
            ex.finishedAt = OffsetDateTime.now();
            ex.key = DbaKey.findById(signed.key().id);
            ex.signature = signed.json();
            r.executions.add(ex);
            Audit.log(user, "EXECUTION_MANUAL", "request", r.id, "notes", notes, "key", Keys.shortFingerprint(signed.key().fingerprint));
        });
    }

    // ---------------------------------------------------------------- vistas
    public static Map<String, Object> summary(ChangeRequest r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.id);
        m.put("code", r.code());
        m.put("title", r.title);
        m.put("status", r.status);
        m.put("connection", Map.of("id", r.connection.id, "name", r.connection.name, "environment", r.connection.environment));
        m.put("project", r.project == null ? null : Map.of("id", r.project.id, "code", r.project.code, "name", r.project.name));
        m.put("target_schemas", new ArrayList<>(schemasOf(r)));
        m.put("operation_types", r.operations.stream().map(o -> o.opType).collect(Collectors.toCollection(TreeSet::new)));
        m.put("requested_by", r.requestedBy);
        m.put("created_at", r.createdAt);
        m.put("updated_at", r.updatedAt);
        m.put("submitted_at", r.submittedAt);
        m.put("decided_at", r.decidedAt);
        m.put("executed_at", r.executedAt);
        return m;
    }

    public Map<String, Object> detail(ChangeRequest r) {
        Map<String, Object> m = summary(r);
        m.put("reason", r.reason);
        m.put("purpose", r.purpose);
        m.put("ticket_ref", r.ticketRef);
        m.put("digest", Guard.digest(r));
        List<Map<String, Object>> ops = new ArrayList<>();
        for (RequestOperation o : r.operations) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("id", o.id);
            x.put("seq", o.seq);
            x.put("type", o.opType);
            x.put("schema", o.targetSchema);
            x.put("object_name", o.objectName);
            x.put("payload", Json.map(o.payload));
            x.put("statements", Json.list(o.statements));
            x.put("status", o.status);
            x.put("message", o.message);
            x.put("executed_at", o.executedAt);
            x.put("statements_done", o.statementsDone);
            ops.add(x);
        }
        m.put("operations", ops);
        String digest = Guard.digest(r);
        List<Map<String, Object>> approvals = new ArrayList<>();
        for (Approval a : r.approvals) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("approver", a.approver);
            x.put("decision", a.decision);
            x.put("comment", a.comment);
            x.put("active", a.active);
            x.put("decided_at", a.decidedAt);
            x.put("key", a.key == null ? null : Keys.shortFingerprint(a.key.fingerprint));
            x.put("signature_valid", a.key != null && keys.validSignature(a.key, a.signature, a.approver, Map.of(
                    "kind", "approval", "request", r.id, "decision", a.decision, "approver", a.approver)));
            Map<String, Object> st = Keys.statementOf(a.signature);
            x.put("signed_current_sql", st != null && digest.equals(st.get("digest")));
            approvals.add(x);
        }
        m.put("approvals", approvals);
        m.put("comments", r.comments.stream().map(c -> {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("id", c.id);
            x.put("author", c.author);
            x.put("body", c.body);
            x.put("created_at", c.createdAt);
            return x;
        }).toList());
        m.put("executions", r.executions.stream().map(e -> {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("id", e.id);
            x.put("executed_by", e.executedBy);
            x.put("mode", e.mode);
            x.put("status", e.status);
            x.put("log", e.log);
            x.put("notes", e.notes);
            x.put("started_at", e.startedAt);
            x.put("finished_at", e.finishedAt);
            x.put("key", e.key == null ? null : Keys.shortFingerprint(e.key.fingerprint));
            x.put("signature_valid", e.key != null && keys.validSignature(e.key, e.signature, e.executedBy, Map.of(
                    "kind", "execution", "request", r.id, "executor", e.executedBy, "mode", e.mode)));
            return x;
        }).toList());
        m.put("required_approvals", r.connection.requiredApprovals);
        return m;
    }
}
