package dbamanager.api;

import dbamanager.core.ApiException;
import dbamanager.core.CurrentUser;
import dbamanager.engine.OracleDdl;
import dbamanager.engine.OracleEngine;
import dbamanager.model.ApproverScope;
import dbamanager.model.DbConnection;
import dbamanager.model.Project;
import dbamanager.ops.Ops;
import dbamanager.service.Keys;
import dbamanager.service.Workflow;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Path("/api")
public class CommonResource {
    @Inject CurrentUser user;
    @Inject Workflow workflow;
    @Inject Keys keys;

    @GET
    @Path("/me")
    @Transactional
    public Map<String, Object> me() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("username", user.username());
        m.put("full_name", user.fullName());
        m.put("email", user.email());
        m.put("roles", user.roles());
        m.put("is_admin", user.isAdmin());
        m.put("is_dba", user.isDba());
        m.put("is_dba_admin", user.isDbaAdmin());
        m.put("scopes", ApproverScope.<ApproverScope>list("username", user.username()).stream()
                .filter(workflow::scopeValid).map(s -> Map.of("connection_id", s.connectionId, "schema_name", s.schemaName)).toList());
        OffsetDateTime until = workflow.selfExecutionUntil(user.username());
        m.put("can_execute", until != null);
        m.put("can_execute_until", until == null || until.equals(OffsetDateTime.MAX) ? null : until);
        m.put("has_key", user.isDba() && keys.hasTrustedKey(user.username()));
        m.put("root_reset", keys.activeReset() == null ? null : KeysResource.resetView(keys.activeReset()));
        return m;
    }

    /** Activa/desactiva la ejecución desde la app SOLO para quien hace la petición (activarla exige su llave). */
    @PUT
    @Path("/me/execution")
    @Transactional
    public Map<String, Object> setMyExecution(Map<String, Object> body) {
        boolean enabled = Body.bool(body, "enabled");
        if (enabled && !"ACTIVAR".equalsIgnoreCase(Body.str(body, "confirm"))) throw ApiException.of(422, "Para activarla escribe ACTIVAR");
        OffsetDateTime until = workflow.setSelfExecution(user, enabled, Body.str(body, "token"), Body.map(body, "assertion"));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("can_execute", enabled);
        m.put("until", until);
        return m;
    }

    @GET
    @Path("/meta")
    public Map<String, Object> meta() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("engines", List.of(Map.of("key", OracleEngine.KEY, "label", OracleEngine.LABEL, "data_types", OracleDdl.DATA_TYPES)));
        m.put("operations", OracleDdl.operationsMeta());
        m.put("object_privileges", Ops.OBJECT_PRIVILEGES);
        m.put("system_privileges", Ops.SYSTEM_PRIVILEGES);
        return m;
    }

    @GET
    @Path("/connections")
    public List<Map<String, Object>> connections() {
        return DbConnection.<DbConnection>list("active = ?1 order by name", true).stream().map(AdminResource::connectionView).toList();
    }

    @GET
    @Path("/projects")
    public List<Map<String, Object>> projects() {
        return Project.<Project>list("order by code").stream().map(p -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", p.id);
            m.put("code", p.code);
            m.put("name", p.name);
            m.put("description", p.description);
            m.put("owner", p.owner);
            m.put("active", p.active);
            return m;
        }).toList();
    }
}
