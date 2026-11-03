package dbamanager.core;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.oidc.AccessTokenCredential;
import io.quarkus.oidc.IdToken;
import io.quarkus.security.identity.SecurityIdentity;
import io.vertx.core.http.HttpServerRequest;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Usuario de la petición: identidad del proveedor OIDC y roles internos de DBA Manager
 * (solicitante, dba, dba_admin, admin), traducidos desde los nombres configurados en Keycloak.
 */
@RequestScoped
public class CurrentUser {
    @Inject SecurityIdentity identity;
    @Inject @IdToken JsonWebToken idToken;
    @Inject Settings settings;
    @Inject HttpServerRequest request;

    private String username;
    private String sub;
    private String fullName = "";
    private String email = "";
    private Set<String> roles = new TreeSet<>();
    private String ip;
    /** Token de acceso (para administrar roles de Keycloak con la identidad del admin). */
    private String accessToken;
    /** Último inicio de sesión real (no la renovación del token), en segundos epoch. */
    private Long authTime;

    @PostConstruct
    void init() {
        if (identity == null || identity.isAnonymous()) return;
        AccessTokenCredential at = identity.getCredential(AccessTokenCredential.class);
        accessToken = at == null ? null : at.getToken();
        JsonNode claims = accessToken == null ? null : decode(accessToken);
        username = text(claims, "preferred_username", idToken.getClaim("preferred_username"));
        sub = text(claims, "sub", idToken.getSubject());
        if (username == null) username = sub;
        fullName = text(claims, "name", idToken.getClaim("name"));
        email = text(claims, "email", idToken.getClaim("email"));
        Object at2 = idToken.getClaim("auth_time");
        if (at2 instanceof Number n) authTime = n.longValue();
        else if (at2 != null) authTime = Long.parseLong(at2.toString());
        if (claims != null && audienceOk(claims)) roles = appRoles(claims);
        ip = request.remoteAddress() == null ? null : request.remoteAddress().hostAddress();
    }

    private boolean audienceOk(JsonNode claims) {
        return settings.audience().map(aud -> {
            JsonNode a = claims.get("aud");
            if (a == null) return false;
            if (a.isArray()) {
                for (JsonNode x : a) if (aud.equals(x.asText())) return true;
                return false;
            }
            return aud.equals(a.asText());
        }).orElse(true);
    }

    private Set<String> appRoles(JsonNode claims) {
        Set<String> tokenRoles = new TreeSet<>();
        JsonNode node = "client".equals(settings.rolesSource)
                ? claims.path("resource_access").path(settings.rolesClient()).path("roles")
                : claims.path("realm_access").path("roles");
        node.forEach(r -> tokenRoles.add(r.asText()));
        Set<String> out = new TreeSet<>();
        for (Map.Entry<String, String> e : settings.roleNames().entrySet()) if (tokenRoles.contains(e.getValue())) out.add(e.getKey());
        return out;
    }

    private static JsonNode decode(String jwt) {
        try {
            String[] parts = jwt.split("\\.");
            return Json.MAPPER.readTree(new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
    }

    private static String text(JsonNode claims, String name, Object fallback) {
        if (claims != null && claims.hasNonNull(name)) return claims.get(name).asText();
        return fallback == null ? null : fallback.toString();
    }

    public String username() { return username; }
    public String sub() { return sub; }
    public String fullName() { return fullName; }
    public String email() { return email; }
    public Set<String> roles() { return roles; }
    public String ip() { return ip; }
    public String accessToken() { return accessToken; }
    public Long authTime() { return authTime; }

    public boolean authenticated() {
        return username != null;
    }

    public boolean isAdmin() {
        return roles.contains(Settings.ADMIN);
    }

    public boolean isDbaAdmin() {
        return roles.contains(Settings.DBA_ADMIN);
    }

    public boolean isDba() {
        return isDbaAdmin() || roles.contains(Settings.DBA);
    }

    public void requireAdmin() {
        if (!isAdmin()) throw ApiException.of(403, "Requiere rol admin");
    }

    public void requireDba() {
        if (!isDba()) throw ApiException.of(403, "Requiere rol dba o dba_admin");
    }

    public void requireDbaAdmin() {
        if (!isDbaAdmin()) throw ApiException.of(403, "Requiere rol dba_admin (DBA principal)");
    }
}
