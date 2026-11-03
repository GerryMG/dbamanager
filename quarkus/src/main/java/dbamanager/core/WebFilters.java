package dbamanager.core;

import dbamanager.model.AppUser;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.vertx.web.RouteFilter;
import io.vertx.ext.web.RoutingContext;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.server.ServerRequestFilter;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Cabeceras de seguridad, protección CSRF y acceso solo con rol de la aplicación. */
public class WebFilters {
    private static final Set<String> SAFE = Set.of("GET", "HEAD", "OPTIONS");
    private static final Map<String, OffsetDateTime> LAST_SEEN = new ConcurrentHashMap<>();
    private static final String CSP = "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; "
            + "connect-src 'self'; font-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'";

    @Inject CurrentUser user;

    @RouteFilter(100)
    void headers(RoutingContext rc) {
        rc.addHeadersEndHandler(v -> {
            var h = rc.response().headers();
            h.set("Content-Security-Policy", CSP);
            h.set("X-Frame-Options", "DENY");
            h.set("X-Content-Type-Options", "nosniff");
            h.set("Referrer-Policy", "no-referrer");
            h.set("Permissions-Policy", "camera=(), microphone=(), geolocation=(), payment=(), usb=()");
            h.set("Cross-Origin-Opener-Policy", "same-origin");
            h.set("Cross-Origin-Resource-Policy", "same-origin");
            if (rc.normalizedPath().startsWith("/api/") || rc.normalizedPath().equals("/")) h.set("Cache-Control", "no-store");
            if ("https".equals(rc.request().scheme())) h.set("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
        });
        rc.next();
    }

    /**
     * Toda escritura en la API exige una cabecera propia (un sitio ajeno no puede enviarla sin que el
     * navegador pida permiso CORS, que no se concede) y, si llega Origin, que sea el mismo sitio.
     */
    @ServerRequestFilter(priority = 10)
    public Response guard(ContainerRequestContext ctx) {
        String path = ctx.getUriInfo().getPath();
        if (!path.startsWith("/api/")) return null;
        if (!SAFE.contains(ctx.getMethod())) {
            if (!"1".equals(ctx.getHeaderString("X-DBAM"))) return deny(403, "Petición rechazada (falta la cabecera de la aplicación)");
            String origin = ctx.getHeaderString("Origin");
            if (origin != null && !sameOrigin(origin, ctx.getUriInfo().getBaseUri())) return deny(403, "Origen no permitido");
        }
        if (!user.authenticated()) return deny(401, "Sesión vencida; vuelve a iniciar sesión");
        if (user.roles().isEmpty()) {
            // Estar en el realm no basta: sin un rol de DBA Manager no se accede a nada.
            return deny(403, "Tu usuario no tiene ningún rol de DBA Manager; pide acceso a un administrador");
        }
        touch();
        return null;
    }

    private static boolean sameOrigin(String origin, URI base) {
        try {
            URI o = URI.create(origin);
            int op = o.getPort() == -1 ? ("https".equals(o.getScheme()) ? 443 : 80) : o.getPort();
            int bp = base.getPort() == -1 ? ("https".equals(base.getScheme()) ? 443 : 80) : base.getPort();
            return o.getHost() != null && o.getHost().equalsIgnoreCase(base.getHost()) && op == bp;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static Response deny(int status, String msg) {
        return Response.status(status).type(MediaType.APPLICATION_JSON).entity(Map.of("detail", msg)).build();
    }

    /** Mantiene el directorio local de usuarios (para mostrar nombres y asignar alcances). */
    private void touch() {
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime last = LAST_SEEN.get(user.username());
        if (last != null && last.isAfter(now.minusMinutes(5))) return;
        QuarkusTransaction.requiringNew().run(() -> {
            AppUser row = AppUser.findById(user.username());
            if (row == null) {
                row = new AppUser();
                row.username = user.username();
            }
            row.sub = user.sub();
            row.fullName = user.fullName();
            row.email = user.email();
            row.roles = String.join(",", user.roles());
            row.lastSeen = now;
            row.persist();
        });
        LAST_SEEN.put(user.username(), now);
    }
}
