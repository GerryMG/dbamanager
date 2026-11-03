package dbamanager.api;

import dbamanager.core.ApiException;
import dbamanager.core.CurrentUser;
import dbamanager.core.Json;
import dbamanager.engine.ConnInfo;
import dbamanager.engine.Finding;
import dbamanager.engine.OracleDdl;
import dbamanager.engine.OracleEngine;
import dbamanager.engine.SqlImport;
import dbamanager.engine.Stmt;
import dbamanager.model.ApproverScope;
import dbamanager.model.AuditLog;
import dbamanager.model.ChangeRequest;
import dbamanager.model.DbConnection;
import dbamanager.model.Project;
import dbamanager.ops.OpParser;
import dbamanager.ops.Ops.Op;
import dbamanager.service.Connections;
import dbamanager.service.Keys;
import dbamanager.service.Workflow;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.panache.common.Parameters;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

@Path("/api/requests")
public class RequestsResource {
    @Inject CurrentUser user;
    @Inject Workflow workflow;
    @Inject Connections connections;
    @Inject Keys keys;

    private static ChangeRequest load(Long id) {
        ChangeRequest r = ChangeRequest.findById(id);
        if (r == null) throw ApiException.of(404, "Solicitud no encontrada");
        return r;
    }

    private static Project project(Map<String, Object> body) {
        Long pid = Body.lng(body, "project_id");
        if (pid == null) return null;
        Project p = Project.findById(pid);
        if (p == null || !p.active) throw ApiException.of(422, "Proyecto inválido o inactivo");
        return p;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rawOps(Map<String, Object> body) {
        List<Object> l = Body.list(body, "operations");
        if (l == null || l.isEmpty()) throw ApiException.of(422, "Agrega al menos una operación");
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : l) {
            if (!(o instanceof Map)) throw ApiException.of(422, "Operación inválida");
            out.add((Map<String, Object>) o);
        }
        return out;
    }

    @GET
    @Transactional
    public List<Map<String, Object>> list(@QueryParam("view") @DefaultValue("all") String view, @QueryParam("status") String status,
                                          @QueryParam("connection_id") Long connectionId, @QueryParam("project_id") Long projectId,
                                          @QueryParam("q") String q, @QueryParam("limit") @DefaultValue("200") int limit) {
        if (!Set.of("all", "mine", "to_review", "to_execute").contains(view)) throw ApiException.of(422, "Vista inválida");
        StringBuilder hql = new StringBuilder("1 = 1");
        Parameters p = new Parameters();
        switch (view) {
            case "mine" -> hql.append(" and requestedBy = :me");
            case "to_review" -> hql.append(" and status = 'PENDING_APPROVAL' and requestedBy <> :me");
            case "to_execute" -> hql.append(" and status in ('APPROVED','FAILED')");
            default -> { }
        }
        if (!view.equals("all") && !view.equals("to_execute")) p.and("me", user.username());
        if (status != null && !status.isBlank()) {
            hql.append(" and status = :status");
            p.and("status", status);
        }
        if (connectionId != null) {
            hql.append(" and connection.id = :cid");
            p.and("cid", connectionId);
        }
        if (projectId != null) {
            hql.append(" and project.id = :pid");
            p.and("pid", projectId);
        }
        if (q != null && !q.isBlank()) {
            hql.append(" and (lower(title) like :ql or targetSchemas like :qu or lower(requestedBy) like :ql)");
            p.and("ql", "%" + q.toLowerCase() + "%").and("qu", "%" + q.toUpperCase() + "%");
        }
        hql.append(" order by id desc");
        List<ChangeRequest> rows = ChangeRequest.<ChangeRequest>find(hql.toString(), p).page(0, Math.min(Math.max(limit, 1), 1000)).list();
        if (view.equals("to_review")) {
            rows = rows.stream().filter(r -> workflow.canReview(user, r)
                    && r.approvals.stream().noneMatch(a -> a.active && a.approver.equals(user.username()))).toList();
        } else if (view.equals("to_execute")) {
            rows = rows.stream().filter(r -> workflow.canExecute(user, r)).toList();
        }
        return rows.stream().map(Workflow::summary).toList();
    }

    /** Valida operaciones y devuelve el SQL que se generaría (sin guardar ni ejecutar). */
    @POST
    @Path("/preview")
    public Map<String, Object> preview(Map<String, Object> body) throws SQLException {
        Long cid = Body.lng(body, "connection_id");
        record Ctx(List<Op> parsed, List<List<Stmt>> rendered, ConnInfo info) {}
        Ctx ctx = QuarkusTransaction.requiringNew().call(() -> {
            DbConnection c = connections.load(cid);
            List<Op> parsed = workflow.parse(rawOps(body));
            return new Ctx(parsed, workflow.render(c, parsed), connections.info(c, "catalog", user.username()));
        });
        List<Map<String, Object>> ops = new ArrayList<>();
        List<String> script = new ArrayList<>();
        for (int i = 0; i < ctx.parsed().size(); i++) {
            Op op = ctx.parsed().get(i);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("seq", i + 1);
            m.put("type", op.type);
            m.put("schema", op.schema);
            m.put("object_name", op.objectName());
            m.put("statements", ctx.rendered().get(i));
            ops.add(m);
            script.add(OracleDdl.script(ctx.rendered().get(i)));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("operations", ops);
        out.put("script", String.join("\n\n", script));
        out.put("findings", Body.bool(body, "preflight") ? OracleEngine.preflight(ctx.info(), ctx.parsed()) : List.of());
        return out;
    }

    /** Convierte un script SQL en operaciones visuales editables (no guarda ni ejecuta nada). */
    @POST
    @Path("/import-sql")
    public Map<String, Object> importSql(Map<String, Object> body) {
        String sql = Body.required(body, "sql", "El script");
        if (sql.length() > 500_000) throw ApiException.of(422, "El script es demasiado grande (máximo 500000 caracteres)");
        String ds = Body.str(body, "default_schema");
        Long cid = Body.lng(body, "connection_id");
        ConnInfo info = QuarkusTransaction.requiringNew().call(() -> connections.info(connections.load(cid), "catalog", user.username()));
        List<SqlImport.Imported> items = OracleEngine.importScript(info, sql, ds == null ? "" : ds);
        List<Map<String, Object>> out = new ArrayList<>();
        Set<String> fallenBack = new HashSet<>();
        for (SqlImport.Imported it : items) {
            Map<String, Object> op = it.op;
            List<String> notes = new ArrayList<>(it.notes);
            if (it.companion && fallenBack.contains(it.source)) continue;  // la restricción ya va dentro del CREATE que quedó como SQL
            // Se valida con un esquema provisional: el usuario puede completar el esquema después.
            Map<String, Object> probe = new LinkedHashMap<>(Json.MAPPER.convertValue(op, Map.class));
            if (probe.get("schema") == null || probe.get("schema").toString().isEmpty()) probe.put("schema", "X");
            try {
                OpParser.parseOne(probe);
            } catch (ApiException e) {
                String errors = errorsText(e);
                if (!"CUSTOM_SQL".equals(op.get("type"))) {
                    notes.add("No se pudo representar visualmente (" + errors + "); queda como SQL personalizado");
                    fallenBack.add(it.source);
                    List<String> parts = new ArrayList<>(List.of(it.source));
                    parts.addAll(it.absorbed);
                    Map<String, Object> custom = new LinkedHashMap<>();
                    custom.put("type", "CUSTOM_SQL");
                    custom.put("schema", op.get("schema") == null ? "" : op.get("schema"));
                    custom.put("description", "Importado del script");
                    custom.put("sql", String.join(";\n", parts));
                    op = custom;
                } else {
                    notes.add(errors);
                }
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("op", op);
            m.put("notes", notes);
            out.add(m);
        }
        long custom = out.stream().filter(o -> "CUSTOM_SQL".equals(((Map<?, ?>) o.get("op")).get("type"))).count();
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("operations", out);
        res.put("recognized", out.size() - custom);
        res.put("custom", custom);
        return res;
    }

    @SuppressWarnings("unchecked")
    private static String errorsText(ApiException e) {
        if (e.detail instanceof Map<?, ?> d && d.get("errors") instanceof List<?> l) {
            List<String> msgs = new ArrayList<>();
            for (Object x : l) msgs.add(String.valueOf(((Map<String, Object>) x).get("message")));
            return String.join("; ", msgs);
        }
        return String.valueOf(e.detail);
    }

    @POST
    @Transactional
    public Map<String, Object> create(Map<String, Object> body) {
        DbConnection c = connections.load(Body.lng(body, "connection_id"));
        ChangeRequest r = workflow.create(user, body, c, project(body));
        return workflow.detail(r);
    }

    @GET
    @Path("/{id}")
    @Transactional
    public Map<String, Object> detail(@PathParam("id") Long id) {
        ChangeRequest r = load(id);
        Map<String, Object> out = workflow.detail(r);
        out.put("permissions", workflow.permissions(user, r));
        out.put("eligible_reviewers", eligibleReviewers(r));
        return out;
    }

    @PUT
    @Path("/{id}")
    @Transactional
    public Map<String, Object> update(@PathParam("id") Long id, Map<String, Object> body) {
        ChangeRequest r = load(id);
        DbConnection c = connections.load(Body.lng(body, "connection_id"));
        workflow.update(user, r, body, c, project(body));
        return workflow.detail(r);
    }

    @POST
    @Path("/{id}/submit")
    @Transactional
    public Map<String, Object> submit(@PathParam("id") Long id) {
        ChangeRequest r = load(id);
        workflow.submit(user, r);
        return workflow.detail(r);
    }

    /** Desafío que el DBA firma con su llave para decidir o ejecutar. */
    @POST
    @Path("/{id}/challenge")
    @Transactional
    public Map<String, Object> challenge(@PathParam("id") Long id, Map<String, Object> body) {
        ChangeRequest r = load(id);
        String purpose = Body.required(body, "purpose", "El propósito");
        if (purpose.equals("approval")) {
            String decision = Body.required(body, "decision", "La decisión");
            String comment = Body.str(body, "comment");
            workflow.checkCanReview(user, r, decision, comment);
            return keys.challenge(user, "approval", workflow.reviewStatement(user, r, decision, comment));
        }
        if (purpose.equals("execution")) {
            String mode = "MANUAL".equals(Body.str(body, "mode")) ? "MANUAL" : "AUTO";
            if (!workflow.canExecute(user, r) || !Workflow.EXECUTABLE.contains(r.status))
                throw ApiException.of(403, "No puedes ejecutar esta solicitud en su estado actual");
            if (!keys.hasTrustedKey(user.username()))
                throw ApiException.coded(403, "KEY_REQUIRED", "Para ejecutar necesitas tu llave de seguridad registrada y avalada (menú Mi llave)");
            return keys.challenge(user, "execution", workflow.executionStatement(user, r, mode, Body.str(body, "notes")));
        }
        throw ApiException.of(422, "Propósito inválido");
    }

    @POST
    @Path("/{id}/review")
    @Transactional
    public Map<String, Object> review(@PathParam("id") Long id, Map<String, Object> body) {
        ChangeRequest r = load(id);
        workflow.review(user, r, Body.required(body, "decision", "La decisión"), Body.str(body, "comment"),
                Body.str(body, "token"), Body.map(body, "assertion"));
        return workflow.detail(r);
    }

    @POST
    @Path("/{id}/cancel")
    @Transactional
    public Map<String, Object> cancel(@PathParam("id") Long id, Map<String, Object> body) {
        ChangeRequest r = load(id);
        workflow.cancel(user, r, Body.str(body, "comment"));
        return workflow.detail(r);
    }

    @POST
    @Path("/{id}/comments")
    @Transactional
    public Map<String, Object> comment(@PathParam("id") Long id, Map<String, Object> body) {
        ChangeRequest r = load(id);
        workflow.comment(user, r, Body.str(body, "body"));
        return workflow.detail(r);
    }

    @GET
    @Path("/{id}/preflight")
    public List<Finding> preflight(@PathParam("id") Long id) throws SQLException {
        record Ctx(List<Op> parsed, ConnInfo info) {}
        Ctx ctx = QuarkusTransaction.requiringNew().call(() -> {
            ChangeRequest r = load(id);
            List<Map<String, Object>> raw = r.operations.stream().map(o -> Json.map(o.payload)).toList();
            return new Ctx(workflow.parse(raw), connections.info(r.connection, "catalog", user.username()));
        });
        return OracleEngine.preflight(ctx.info(), ctx.parsed());
    }

    @GET
    @Path("/{id}/script")
    @Produces(MediaType.TEXT_PLAIN)
    @Transactional
    public Response script(@PathParam("id") Long id) {
        ChangeRequest r = load(id);
        return Response.ok(workflow.buildScript(r)).header("Content-Disposition", "attachment; filename=\"" + r.code() + ".sql\"").build();
    }

    @POST
    @Path("/{id}/execute")
    public Map<String, Object> execute(@PathParam("id") Long id, Map<String, Object> body) {
        Map<String, Object> result = workflow.execute(user, id, Body.str(body, "confirm_code"), Body.str(body, "token"), Body.map(body, "assertion"));
        Map<String, Object> out = new LinkedHashMap<>(result);
        out.put("request", QuarkusTransaction.requiringNew().call(() -> workflow.detail(load(id))));
        return out;
    }

    @POST
    @Path("/{id}/mark-executed")
    public Map<String, Object> markExecuted(@PathParam("id") Long id, Map<String, Object> body) {
        workflow.markExecuted(user, id, Body.str(body, "confirm_code"), Body.str(body, "notes"), Body.str(body, "token"), Body.map(body, "assertion"));
        return QuarkusTransaction.requiringNew().call(() -> workflow.detail(load(id)));
    }

    @GET
    @Path("/{id}/history")
    public List<Map<String, Object>> history(@PathParam("id") Long id) {
        return AuditLog.<AuditLog>list("entityType = 'request' and entityId = ?1 order by at, id", String.valueOf(id)).stream().map(a -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("at", a.at);
            m.put("username", a.username);
            m.put("action", a.action);
            m.put("details", Json.map(a.details));
            return m;
        }).toList();
    }

    /** DBAs con alcance firmado sobre todos los esquemas (además de los dba_admin). */
    private List<String> eligibleReviewers(ChangeRequest r) {
        Set<String> schemas = Workflow.schemasOf(r);
        Map<String, Set<String>> byUser = new TreeMap<>();
        for (ApproverScope s : ApproverScope.<ApproverScope>list("connectionId", r.connection.id)) {
            if (workflow.scopeValid(s)) byUser.computeIfAbsent(s.username, x -> new TreeSet<>()).add(s.schemaName.toUpperCase());
        }
        List<String> out = new ArrayList<>();
        byUser.forEach((u, s) -> {
            if ((s.contains("*") || s.containsAll(schemas)) && !u.equals(r.requestedBy)) out.add(u);
        });
        return out;
    }
}
