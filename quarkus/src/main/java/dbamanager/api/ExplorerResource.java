package dbamanager.api;

import dbamanager.core.ApiException;
import dbamanager.core.CurrentUser;
import dbamanager.engine.ConnInfo;
import dbamanager.engine.OracleEngine;
import dbamanager.model.DbConnection;
import dbamanager.ops.V;
import dbamanager.service.Connections;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

/** Exploración de estructura (solo diccionario de datos, nunca datos de negocio). */
@Path("/api/explorer/{cid}")
public class ExplorerResource {
    @Inject CurrentUser user;
    @Inject Connections connections;

    private ConnInfo info(Long cid) {
        return QuarkusTransaction.requiringNew().call(() -> {
            DbConnection c = connections.load(cid);
            return connections.info(c, "catalog", user.username());
        });
    }

    private static String ident(String v) {
        V.Errs e = new V.Errs();
        String out = V.ident(e, "", v);
        if (!e.empty()) throw ApiException.of(422, (String) e.list.get(0).get("message"));
        return out;
    }

    @GET
    @Path("/schemas")
    public List<Map<String, Object>> schemas(@PathParam("cid") Long cid) throws SQLException {
        return OracleEngine.listSchemas(info(cid));
    }

    @GET
    @Path("/schemas/{schema}/objects")
    public List<Map<String, Object>> objects(@PathParam("cid") Long cid, @PathParam("schema") String schema) throws SQLException {
        return OracleEngine.listObjects(info(cid), ident(schema));
    }

    @GET
    @Path("/schemas/{schema}/tables/{name}")
    public Map<String, Object> table(@PathParam("cid") Long cid, @PathParam("schema") String schema, @PathParam("name") String name) throws SQLException {
        return OracleEngine.describeTable(info(cid), ident(schema), ident(name));
    }

    @GET
    @Path("/schemas/{schema}/user")
    public Map<String, Object> schemaUser(@PathParam("cid") Long cid, @PathParam("schema") String schema) throws SQLException {
        return OracleEngine.describeUser(info(cid), ident(schema));
    }

    @GET
    @Path("/schemas/{schema}/ddl")
    public Map<String, Object> ddl(@PathParam("cid") Long cid, @PathParam("schema") String schema,
                                   @QueryParam("type") String type, @QueryParam("name") String name) throws SQLException {
        if (type == null || name == null) throw ApiException.of(422, "Indica type y name");
        return Map.of("ddl", OracleEngine.getDdl(info(cid), ident(schema), type, ident(name)));
    }

    @GET
    @Path("/search")
    public List<Map<String, Object>> search(@PathParam("cid") Long cid, @QueryParam("q") String q) throws SQLException {
        if (q == null || q.strip().length() < 2 || q.length() > 60) throw ApiException.of(422, "La búsqueda debe tener entre 2 y 60 caracteres");
        return OracleEngine.search(info(cid), q.strip());
    }
}
