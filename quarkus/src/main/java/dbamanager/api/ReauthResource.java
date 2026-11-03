package dbamanager.api;

import dbamanager.model.UserSetting;
import dbamanager.service.Audit;
import io.quarkus.oidc.IdToken;
import io.quarkus.oidc.OidcSession;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Reautenticación: Keycloak vuelve a pedir la contraseña (prompt=login) aunque haya sesión abierta.
 * Solo se registra si el inicio de sesión ocurrió hace menos de dos minutos.
 */
@Path("/reauth")
public class ReauthResource {
    @Inject @IdToken JsonWebToken idToken;
    @Inject OidcSession session;

    @GET
    @Transactional
    public Response reauth() {
        String username = idToken.getClaim("preferred_username");
        Object at = idToken.getClaim("auth_time");
        long authTime = at instanceof Number n ? n.longValue() : at == null ? 0 : Long.parseLong(at.toString());
        if (username != null && authTime > Instant.now().minusSeconds(120).getEpochSecond()) {
            UserSetting s = UserSetting.findById(username);
            if (s == null) {
                s = new UserSetting();
                s.username = username;
                s.persist();
            }
            s.reauthAt = OffsetDateTime.ofInstant(Instant.ofEpochSecond(authTime), ZoneOffset.UTC);
            Audit.write(username, null, "REAUTHENTICATED", "user", username);
        }
        // Solo se cierra la sesión local de esta ruta; la de la aplicación sigue igual.
        session.logout().await().indefinitely();
        return Response.seeOther(URI.create("/#/llaves")).build();
    }
}
