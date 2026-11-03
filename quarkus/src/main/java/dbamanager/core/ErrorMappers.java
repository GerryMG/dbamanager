package dbamanager.core;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

import java.sql.SQLException;
import java.util.Map;
import java.util.NoSuchElementException;

/** Errores -> respuesta JSON uniforme {"detail": ...}. */
public class ErrorMappers {
    private static final Logger LOG = Logger.getLogger(ErrorMappers.class);

    private static Response json(int status, Object detail) {
        return Response.status(status).type(MediaType.APPLICATION_JSON).entity(Map.of("detail", detail)).build();
    }

    @ServerExceptionMapper
    public Response api(ApiException e) {
        return json(e.status, e.detail);
    }

    @ServerExceptionMapper
    public Response invalid(IllegalArgumentException e) {
        return json(422, e.getMessage());
    }

    @ServerExceptionMapper
    public Response forbidden(SecurityException e) {
        return json(403, e.getMessage());
    }

    @ServerExceptionMapper
    public Response notFound(NoSuchElementException e) {
        return json(404, e.getMessage());
    }

    @ServerExceptionMapper
    public Response database(SQLException e) {
        return json(502, "Error de la base de datos: " + e.getMessage().strip());
    }

    @ServerExceptionMapper
    public Response oracle(dbamanager.engine.OracleEngine.DbError e) {
        return json(502, "Error de la base de datos: " + e.sql.getMessage().strip());
    }

    @ServerExceptionMapper
    public Response state(IllegalStateException e) {
        LOG.warn("Estado inválido: " + e.getMessage());
        return json(409, e.getMessage());
    }

    @ServerExceptionMapper
    public Response web(WebApplicationException e) {
        int status = e.getResponse().getStatus();
        return json(status, status == 404 ? "No encontrado" : e.getMessage());
    }

    @ServerExceptionMapper
    public Response unexpected(RuntimeException e) {
        LOG.error("Error no controlado", e);
        return json(500, "Error interno; revisa el log del servidor");
    }
}
