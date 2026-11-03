package dbamanager.api;

import dbamanager.core.ApiException;
import dbamanager.core.Crypto;
import dbamanager.core.CurrentUser;
import dbamanager.core.Json;
import dbamanager.core.Settings;
import dbamanager.engine.ConnInfo;
import dbamanager.engine.OracleEngine;
import dbamanager.model.AppUser;
import dbamanager.model.ApproverScope;
import dbamanager.model.AuditLog;
import dbamanager.model.DbConnection;
import dbamanager.model.Project;
import dbamanager.service.Audit;
import dbamanager.service.Backup;
import dbamanager.service.Connections;
import dbamanager.service.KeycloakAdmin;
import dbamanager.service.Keys;
import dbamanager.service.Workflow;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.panache.common.Parameters;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;

import java.nio.file.Files;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

@Path("/api/admin")
public class AdminResource {
    @Inject CurrentUser user;
    @Inject Crypto crypto;
    @Inject Settings settings;
    @Inject Connections connections;
    @Inject Keys keys;
    @Inject Workflow workflow;
    @Inject KeycloakAdmin keycloak;
    @Inject Backup backup;

    // ------------------------------------------------------------------ conexiones
    static Map<String, Object> connectionView(DbConnection c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.id);
        m.put("name", c.name);
        m.put("engine", c.engine);
        m.put("environment", c.environment);
        m.put("description", c.description);
        m.put("host", c.host);
        m.put("port", c.port);
        m.put("service_name", c.serviceName);
        m.put("catalog_user", c.catalogUser);
        m.put("exec_user", c.execUser);
        m.put("has_exec_account", c.execUser != null && c.execPasswordEnc != null);
        m.put("allow_execution", c.allowExecution);
        m.put("use_tls", c.useTls);
        m.put("required_approvals", c.requiredApprovals);
        m.put("active", c.active);
        m.put("server_version", c.serverVersion);
        return m;
    }

    @GET
    @Path("/connections")
    public List<Map<String, Object>> listConnections() {
        user.requireAdmin();
        return DbConnection.<DbConnection>list("order by name").stream().map(AdminResource::connectionView).toList();
    }

    private void apply(DbConnection c, Map<String, Object> b) {
        String name = Body.required(b, "name", "El nombre");
        String env = Body.required(b, "environment", "El ambiente");
        if (name.length() > 100 || env.length() > 20) throw ApiException.of(422, "Nombre (máx. 100) o ambiente (máx. 20) demasiado largo");
        String engine = Objects.requireNonNullElse(Body.str(b, "engine"), "oracle");
        if (!engine.equals(OracleEngine.KEY)) throw ApiException.of(422, "Motor no soportado: " + engine);
        String host = Body.required(b, "host", "El host");
        int port = Body.integer(b, "port", 1521);
        if (port < 1 || port > 65535) throw ApiException.of(422, "Puerto inválido");
        String service = Body.required(b, "service_name", "El servicio");
        int approvals = Body.integer(b, "required_approvals", 1);
        if (approvals < 1 || approvals > 5) throw ApiException.of(422, "Las aprobaciones requeridas van de 1 a 5");
        if (!Objects.equals(c.host, host) || c.port != port || !Objects.equals(c.serviceName, service)) c.serverVersion = null;  // otro servidor
        c.name = name;
        c.engine = engine;
        c.environment = env.toUpperCase();
        c.description = Body.str(b, "description");
        c.host = host;
        c.port = port;
        c.serviceName = service;
        c.catalogUser = Body.required(b, "catalog_user", "La cuenta de catálogo");
        c.execUser = Body.str(b, "exec_user");
        c.allowExecution = Body.bool(b, "allow_execution");
        c.useTls = Body.bool(b, "use_tls");
        c.requiredApprovals = approvals;
        c.active = Body.bool(b, "active", true);
        String catalogPassword = Body.str(b, "catalog_password");
        String execPassword = Body.str(b, "exec_password");
        if (catalogPassword != null) c.catalogPasswordEnc = crypto.encrypt(catalogPassword);
        if (execPassword != null) c.execPasswordEnc = crypto.encrypt(execPassword);
        if (c.execUser == null) {
            c.execPasswordEnc = null;
            c.allowExecution = false;
        }
    }

    private static Map<String, Object> withoutPasswords(Map<String, Object> b) {
        Map<String, Object> m = new LinkedHashMap<>(b);
        m.remove("catalog_password");
        m.remove("exec_password");
        return m;
    }

    @POST
    @Path("/connections")
    @Transactional
    public Map<String, Object> createConnection(Map<String, Object> body) {
        user.requireAdmin();
        if (Body.str(body, "catalog_password") == null) throw ApiException.of(422, "La contraseña de la cuenta de catálogo es obligatoria");
        if (DbConnection.count("name", Body.str(body, "name")) > 0) throw ApiException.of(409, "Ya existe una conexión con ese nombre");
        DbConnection c = new DbConnection();
        c.createdBy = user.username();
        apply(c, body);
        c.persist();
        Audit.log(user, "CONNECTION_CREATED", "connection", c.id, "data", withoutPasswords(body));
        return connectionView(c);
    }

    @PUT
    @Path("/connections/{id}")
    @Transactional
    public Map<String, Object> updateConnection(@PathParam("id") Long id, Map<String, Object> body) {
        user.requireAdmin();
        DbConnection c = connections.load(id, false);
        if (DbConnection.count("name = ?1 and id <> ?2", Body.str(body, "name"), id) > 0) throw ApiException.of(409, "Ya existe una conexión con ese nombre");
        apply(c, body);
        Audit.log(user, "CONNECTION_UPDATED", "connection", c.id, "data", withoutPasswords(body),
                "catalog_password_changed", Body.str(body, "catalog_password") != null, "exec_password_changed", Body.str(body, "exec_password") != null);
        return connectionView(c);
    }

    @POST
    @Path("/connections/{id}/test")
    public Map<String, Object> testConnection(@PathParam("id") Long id, @QueryParam("role") @DefaultValue("catalog") String role) {
        user.requireAdmin();
        if (!Set.of("catalog", "exec").contains(role)) throw ApiException.of(422, "Rol inválido");
        ConnInfo info = QuarkusTransaction.requiringNew().call(() -> connections.info(connections.load(id, false), role, user.username()));
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            Map<String, Object> r = OracleEngine.testConnection(info);
            Object v = r.get("version");
            if (v != null) QuarkusTransaction.requiringNew().run(() -> {
                DbConnection c = DbConnection.findById(id);
                if (!v.equals(c.serverVersion)) c.serverVersion = v.toString();
            });
            out.put("ok", true);
            out.putAll(r);
        } catch (Exception e) {
            out.put("ok", false);
            out.put("error", e instanceof OracleEngine.DbError d ? d.sql.getMessage() : e.getMessage());
        }
        return out;
    }

    // ------------------------------------------------------------------ proyectos
    private static void applyProject(Project p, Map<String, Object> b) {
        String code = Body.required(b, "code", "El código");
        String name = Body.required(b, "name", "El nombre");
        if (code.length() < 2 || code.length() > 30 || name.length() < 2 || name.length() > 200)
            throw ApiException.of(422, "Código (2–30) o nombre (2–200) con longitud inválida");
        p.code = code.toUpperCase();
        p.name = name;
        p.description = Body.str(b, "description");
        p.owner = Body.str(b, "owner");
        p.active = Body.bool(b, "active", true);
    }

    @POST
    @Path("/projects")
    @Transactional
    public Map<String, Object> createProject(Map<String, Object> body) {
        user.requireDba();
        if (Project.count("code", Objects.toString(Body.str(body, "code"), "").toUpperCase()) > 0)
            throw ApiException.of(409, "Ya existe un proyecto con ese código");
        Project p = new Project();
        applyProject(p, body);
        p.persist();
        Audit.log(user, "PROJECT_CREATED", "project", p.id, "data", body);
        return Map.of("id", p.id);
    }

    @PUT
    @Path("/projects/{id}")
    @Transactional
    public Map<String, Object> updateProject(@PathParam("id") Long id, Map<String, Object> body) {
        user.requireDba();
        Project p = Project.findById(id);
        if (p == null) throw ApiException.of(404, "Proyecto no encontrado");
        applyProject(p, body);
        Audit.log(user, "PROJECT_UPDATED", "project", p.id, "data", body);
        return Map.of("id", p.id);
    }

    // ------------------------------------------------------------------ alcances de DBAs (los firma un DBA principal)
    @GET
    @Path("/scopes")
    @Transactional
    public List<Map<String, Object>> listScopes() {
        if (!user.isDba() && !user.isAdmin()) throw ApiException.of(403, "Requiere rol dba o admin");
        return ApproverScope.<ApproverScope>list("order by username, connectionId").stream().map(s -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.id);
            m.put("username", s.username);
            m.put("connection_id", s.connectionId);
            m.put("schema_name", s.schemaName);
            m.put("granted_by", s.grantedBy);
            m.put("granted_at", s.grantedAt);
            m.put("key", s.key == null ? null : Keys.shortFingerprint(s.key.fingerprint));
            m.put("signature_valid", workflow.scopeValid(s));
            return m;
        }).toList();
    }

    private Map<String, Object> scopeStatement(String username, Long connectionId, String schema) {
        Map<String, Object> st = new LinkedHashMap<>();
        st.put("username", username);
        st.put("connection_id", connectionId);
        st.put("schema", schema);
        st.put("granted_by", user.username());
        return st;
    }

    private record ScopeIn(String username, Long connectionId, String schema) {}

    private ScopeIn scopeIn(Map<String, Object> body) {
        user.requireDbaAdmin();
        String username = Body.required(body, "username", "El usuario");
        Long cid = Body.lng(body, "connection_id");
        connections.load(cid, false);
        String schema = Body.required(body, "schema_name", "El esquema").toUpperCase();
        if (schema.length() > 128) throw ApiException.of(422, "Esquema demasiado largo");
        if (username.equals(user.username())) throw ApiException.of(403, "No puedes asignarte alcances de aprobación a ti mismo");
        if (!keys.hasTrustedKey(user.username()))
            throw ApiException.coded(403, "KEY_REQUIRED", "Para otorgar alcances necesitas tu llave de seguridad (menú Mi llave)");
        if (ApproverScope.count("username = ?1 and connectionId = ?2 and schemaName = ?3", username, cid, schema) > 0)
            throw ApiException.of(409, "Ese alcance ya existe");
        return new ScopeIn(username, cid, schema);
    }

    @POST
    @Path("/scopes/challenge")
    @Transactional
    public Map<String, Object> scopeChallenge(Map<String, Object> body) {
        ScopeIn in = scopeIn(body);
        return keys.challenge(user, "scope", scopeStatement(in.username(), in.connectionId(), in.schema()));
    }

    @POST
    @Path("/scopes")
    @Transactional
    public Map<String, Object> addScope(Map<String, Object> body) {
        ScopeIn in = scopeIn(body);
        Keys.Signed s = keys.sign(user, "scope", Body.str(body, "token"), Body.map(body, "assertion"));
        Map<String, Object> st = Keys.statementOf(s.json());
        Map<String, Object> expected = scopeStatement(in.username(), in.connectionId(), in.schema());
        for (Map.Entry<String, Object> e : expected.entrySet()) {
            if (!Objects.equals(String.valueOf(st.get(e.getKey())), String.valueOf(e.getValue())))
                throw ApiException.of(409, "Lo firmado no corresponde a este alcance");
        }
        ApproverScope sc = new ApproverScope();
        sc.username = in.username();
        sc.connectionId = in.connectionId();
        sc.schemaName = in.schema();
        sc.grantedBy = user.username();
        sc.key = s.key();
        sc.signature = s.json();
        sc.persist();
        Audit.log(user, "SCOPE_GRANTED", "scope", sc.id, "username", sc.username, "connection_id", sc.connectionId,
                "schema_name", sc.schemaName, "key", Keys.shortFingerprint(s.key().fingerprint));
        return Map.of("id", sc.id);
    }

    @DELETE
    @Path("/scopes/{id}")
    @Transactional
    public Map<String, Object> deleteScope(@PathParam("id") Long id) {
        user.requireDbaAdmin();
        ApproverScope s = ApproverScope.findById(id);
        if (s == null) throw ApiException.of(404, "Alcance no encontrado");
        Audit.log(user, "SCOPE_REVOKED", "scope", s.id, "username", s.username, "connection_id", s.connectionId, "schema_name", s.schemaName);
        s.delete();
        return Map.of("ok", true);
    }

    @GET
    @Path("/users")
    public List<Map<String, Object>> listUsers() {
        if (!user.isDba() && !user.isAdmin()) throw ApiException.of(403, "Requiere rol dba o admin");
        return AppUser.<AppUser>list("order by username").stream().map(u -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("username", u.username);
            m.put("full_name", u.fullName);
            m.put("email", u.email);
            m.put("roles", u.roles == null || u.roles.isEmpty() ? List.of() : List.of(u.roles.split(",")));
            m.put("last_seen", u.lastSeen);
            return m;
        }).toList();
    }

    // ------------------------------------------------------------------ usuarios y roles (Keycloak)
    @GET
    @Path("/identity")
    public Map<String, Object> identity() {
        user.requireAdmin();
        return keycloak.status();
    }

    @GET
    @Path("/idp-users")
    public List<Map<String, Object>> idpUsers(@QueryParam("search") String search, @QueryParam("first") @DefaultValue("0") int first,
                                              @QueryParam("limit") @DefaultValue("50") int limit) {
        user.requireAdmin();
        if (search != null && search.length() > 100) throw ApiException.of(422, "Búsqueda demasiado larga");
        List<Map<String, Object>> users = keycloak.listUsers(search, Math.max(first, 0), Math.min(Math.max(limit, 1), 200));
        Map<String, Integer> scopes = new HashMap<>();
        for (ApproverScope s : ApproverScope.<ApproverScope>listAll()) scopes.merge(s.username, 1, Integer::sum);
        Map<String, OffsetDateTime> seen = new HashMap<>();
        for (AppUser u : AppUser.<AppUser>listAll()) seen.put(u.username, u.lastSeen);
        for (Map<String, Object> u : users) {
            u.put("scopes", scopes.getOrDefault((String) u.get("username"), 0));
            u.put("last_seen", seen.get((String) u.get("username")));
        }
        return users;
    }

    @PUT
    @Path("/idp-users/{userId}/roles")
    @Transactional
    public Map<String, Object> setRoles(@PathParam("userId") String userId, Map<String, Object> body) {
        user.requireAdmin();
        List<Object> raw = Body.list(body, "roles");
        Set<String> wanted = new TreeSet<>();
        if (raw != null) raw.forEach(r -> wanted.add(String.valueOf(r)));
        Set<String> unknown = new TreeSet<>(wanted);
        unknown.removeAll(settings.roleNames().keySet());
        if (!unknown.isEmpty()) throw ApiException.of(422, "Roles desconocidos: " + String.join(", ", unknown));
        Map<String, Object> target = keycloak.getUser(userId);
        String username = (String) target.get("username");
        if (username.equals(user.username())) throw ApiException.of(403, "No puedes cambiar tus propios roles; pídeselo a otro administrador");
        List<List<String>> changes = keycloak.setRoles(userId, wanted);
        List<String> effective = keycloak.userRoles(userId);
        AppUser row = AppUser.findById(username);
        if (row != null) row.roles = String.join(",", effective);
        if (!changes.get(0).isEmpty() || !changes.get(1).isEmpty())
            Audit.log(user, "ROLES_CHANGED", "user", username, "added", changes.get(0), "removed", changes.get(1));
        String note = null;
        if (effective.contains("dba") && !effective.contains("dba_admin") && ApproverScope.count("username", username) == 0)
            note = "Tiene rol dba pero ningún alcance de esquemas: un DBA principal debe asignárselo para que pueda aprobar";
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("username", username);
        out.put("roles", effective);
        out.put("added", changes.get(0));
        out.put("removed", changes.get(1));
        out.put("note", note);
        return out;
    }

    // ------------------------------------------------------------------ bitácora
    @GET
    @Path("/audit")
    public List<Map<String, Object>> audit(@QueryParam("username") String username, @QueryParam("action") String action,
                                           @QueryParam("entity_type") String entityType, @QueryParam("limit") @DefaultValue("300") int limit) {
        if (!user.isDba() && !user.isAdmin()) throw ApiException.of(403, "Requiere rol dba o admin");
        StringBuilder q = new StringBuilder("1 = 1");
        Parameters p = new Parameters();
        if (username != null && !username.isBlank()) {
            q.append(" and username = :u");
            p.and("u", username);
        }
        if (action != null && !action.isBlank()) {
            q.append(" and action = :a");
            p.and("a", action);
        }
        if (entityType != null && !entityType.isBlank()) {
            q.append(" and entityType = :e");
            p.and("e", entityType);
        }
        q.append(" order by id desc");
        List<Map<String, Object>> out = new ArrayList<>();
        for (AuditLog a : AuditLog.<AuditLog>find(q.toString(), p).page(0, Math.min(Math.max(limit, 1), 2000)).list()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", a.id);
            m.put("at", a.at);
            m.put("username", a.username);
            m.put("action", a.action);
            m.put("entity_type", a.entityType);
            m.put("entity_id", a.entityId);
            m.put("details", Json.map(a.details));
            m.put("ip", a.ip);
            out.add(m);
        }
        return out;
    }

    // ------------------------------------------------------------------ respaldo / migración de metadata
    @GET
    @Path("/backup")
    public Response exportBackup() throws SQLException {
        user.requireAdmin();
        Map<String, Object> data = backup.export();
        Map<String, Integer> rows = new LinkedHashMap<>();
        ((Map<?, ?>) data.get("tables")).forEach((k, v) -> rows.put((String) k, ((List<?>) v).size()));
        QuarkusTransaction.requiringNew().run(() -> Audit.log(user, "METADATA_EXPORTED", "metadata", null, "rows", rows));
        String stamp = OffsetDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        return Response.ok(Json.write(data), MediaType.APPLICATION_JSON)
                .header("Content-Disposition", "attachment; filename=\"dbamanager-metadata-" + stamp + ".json\"").build();
    }

    @POST
    @Path("/restore")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public Map<String, Object> restore(@RestForm("file") FileUpload file, @QueryParam("replace") @DefaultValue("false") boolean replace) throws Exception {
        user.requireAdmin();
        if (replace && !settings.metadataRestoreReplace)
            throw ApiException.of(403, "Reemplazar la metadata existente está deshabilitado (METADATA_RESTORE_REPLACE=false): "
                    + "borraría la bitácora actual. Solo se restaura sobre una metadata vacía");
        if (file == null) throw ApiException.of(422, "Adjunta el archivo de respaldo");
        if (file.size() > settings.restoreMaxMb * 1024L * 1024L)
            throw ApiException.of(413, "El respaldo excede " + settings.restoreMaxMb + " MB (RESTORE_MAX_MB)");
        Map<String, Object> data;
        try {
            data = Json.map(Files.readString(file.uploadedFile()));
        } catch (IllegalStateException e) {
            throw ApiException.of(422, "El archivo no es JSON válido");
        }
        if (data == null) throw ApiException.of(422, "El archivo está vacío");
        Map<String, Object> counts = backup.restore(data, replace);
        QuarkusTransaction.requiringNew().run(() -> Audit.log(user, "METADATA_IMPORTED", "metadata", null,
                "replace", replace, "rows", counts, "file", file.fileName()));
        return Map.of("ok", true, "rows", counts);
    }
}
