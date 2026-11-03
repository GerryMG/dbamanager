package dbamanager.service;

import com.fasterxml.jackson.core.type.TypeReference;
import dbamanager.core.ApiException;
import dbamanager.core.CurrentUser;
import dbamanager.core.Json;
import dbamanager.core.Settings;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Administración de roles en Keycloak (API REST de administración del realm). Modos
 * (KEYCLOAK_ROLES_ADMIN_MODE): token = con el token del admin que inició sesión (necesita en
 * realm-management view-users, query-users y manage-users, y view-clients si los roles son de cliente);
 * service-account = cliente confidencial con esos mismos roles. Solo toca los 4 roles de la app.
 */
@ApplicationScoped
public class KeycloakAdmin {
    private static final String PERMS = "view-users, query-users y manage-users del cliente realm-management";
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final Map<String, String> clientUuid = new ConcurrentHashMap<>();
    private volatile String saToken;
    private volatile long saTokenExp;

    @Inject Settings settings;
    @Inject CurrentUser user;

    private void enabled() {
        if (!settings.roleManagementEnabled())
            throw ApiException.of(409, "La administración de roles desde DBA Manager está deshabilitada "
                    + "(KEYCLOAK_ROLES_ADMIN_MODE=off o falta el cliente de servicio)");
    }

    private String noPerms() {
        return "token".equals(settings.rolesAdminMode)
                ? "Tu usuario de Keycloak no tiene permisos para administrar usuarios del realm: pide que te asignen " + PERMS
                : "La cuenta de servicio no tiene permisos suficientes: asígnale " + PERMS;
    }

    private String adminBase() {
        return settings.keycloakBackendUrl() + "/admin/realms/" + settings.keycloakRealm;
    }

    private String token() {
        enabled();
        if ("token".equals(settings.rolesAdminMode)) {
            if (user.accessToken() == null) throw ApiException.of(401, "Falta el token del usuario para administrar roles");
            return user.accessToken();
        }
        if (saToken != null && saTokenExp - System.currentTimeMillis() / 1000 > 30) return saToken;
        String form = "grant_type=client_credentials&client_id=" + enc(settings.adminClientId()) + "&client_secret=" + enc(settings.adminClientSecret());
        HttpResponse<String> r = send(HttpRequest.newBuilder(URI.create(settings.keycloakBackendUrl() + "/realms/" + settings.keycloakRealm
                + "/protocol/openid-connect/token")).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form)).build());
        if (r.statusCode() != 200) throw ApiException.of(502, "Keycloak rechazó la cuenta de servicio (" + r.statusCode() + "): revisa client id/secret");
        Map<String, Object> data = Json.map(r.body());
        saToken = (String) data.get("access_token");
        saTokenExp = System.currentTimeMillis() / 1000 + ((Number) data.getOrDefault("expires_in", 60)).longValue();
        return saToken;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private HttpResponse<String> send(HttpRequest req) {
        try {
            return http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw ApiException.of(502, "No se pudo contactar a Keycloak: " + e.getMessage());
        }
    }

    private String call(String method, String path, Object body) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(adminBase() + path)).timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + token());
        if (body != null) b.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(Json.write(body)));
        else b.method(method, HttpRequest.BodyPublishers.noBody());
        HttpResponse<String> r = send(b.build());
        if (r.statusCode() == 401 || r.statusCode() == 403) throw ApiException.of(403, noPerms());
        if (r.statusCode() == 404) throw ApiException.of(404, "No encontrado en Keycloak");
        if (r.statusCode() >= 400) {
            String t = r.body() == null ? "" : r.body();
            throw ApiException.of(502, "Error de Keycloak " + r.statusCode() + ": " + (t.length() > 200 ? t.substring(0, 200) : t));
        }
        return r.body();
    }

    private List<Map<String, Object>> getList(String path) {
        try {
            String body = call("GET", path, null);
            return body == null || body.isBlank() ? List.of() : Json.MAPPER.readValue(body, new TypeReference<>() {});
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw ApiException.of(502, "Respuesta inválida de Keycloak");
        }
    }

    /** UUID interno del cliente cuando los roles son de cliente. */
    private String rolesClientUuid() {
        String cid = settings.rolesClient();
        return clientUuid.computeIfAbsent(cid, x -> {
            List<Map<String, Object>> found = getList("/clients?clientId=" + enc(x));
            if (found.isEmpty()) throw ApiException.of(409, "No existe el cliente " + x + " en Keycloak");
            return (String) found.get(0).get("id");
        });
    }

    private String mappingPath(String userId) {
        if ("client".equals(settings.rolesSource)) return "/users/" + userId + "/role-mappings/clients/" + rolesClientUuid();
        return "/users/" + userId + "/role-mappings/realm";
    }

    /** Roles asignables o asignados (role-mappings/available no requiere view-realm). */
    private Map<String, Map<String, Object>> roleReprs(String userId) {
        Map<String, Map<String, Object>> reprs = new LinkedHashMap<>();
        for (String p : List.of(mappingPath(userId), mappingPath(userId) + "/available")) {
            for (Map<String, Object> r : getList(p)) reprs.put((String) r.get("name"), Map.of("id", r.get("id"), "name", r.get("name")));
        }
        return reprs;
    }

    private List<String> toInternal(Set<String> external) {
        return settings.roleNames().entrySet().stream().filter(e -> external.contains(e.getValue())).map(Map.Entry::getKey).sorted().toList();
    }

    public List<String> userRoles(String userId) {
        // /composite incluye roles heredados por grupos o roles compuestos
        return toInternal(getList(mappingPath(userId) + "/composite").stream().map(r -> (String) r.get("name")).collect(Collectors.toSet()));
    }

    public List<String> directRoles(String userId) {
        return toInternal(getList(mappingPath(userId)).stream().map(r -> (String) r.get("name")).collect(Collectors.toSet()));
    }

    public List<Map<String, Object>> listUsers(String search, int first, int limit) {
        String q = "/users?first=" + first + "&max=" + limit + "&briefRepresentation=true" + (search == null || search.isBlank() ? "" : "&search=" + enc(search));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> u : getList(q)) {
            String username = (String) u.getOrDefault("username", "");
            if (username.startsWith("service-account-")) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            String id = (String) u.get("id");
            m.put("id", id);
            m.put("username", username);
            m.put("email", u.get("email"));
            m.put("full_name", ((u.get("firstName") == null ? "" : u.get("firstName")) + " " + (u.get("lastName") == null ? "" : u.get("lastName"))).strip());
            m.put("enabled", u.getOrDefault("enabled", true));
            m.put("roles", userRoles(id));
            m.put("direct_roles", directRoles(id));
            out.add(m);
        }
        return out;
    }

    public Map<String, Object> getUser(String userId) {
        Map<String, Object> u = Json.map(call("GET", "/users/" + enc(userId), null));
        return Map.of("id", u.get("id"), "username", u.get("username"));
    }

    /** Ajusta los roles directos del usuario a `wanted`. Devuelve [agregados, quitados]. */
    public List<List<String>> setRoles(String userId, Set<String> wanted) {
        Map<String, String> names = settings.roleNames();
        Set<String> current = new TreeSet<>(directRoles(userId));
        List<String> add = wanted.stream().filter(r -> !current.contains(r)).sorted().toList();
        List<String> remove = current.stream().filter(r -> !wanted.contains(r)).sorted().toList();
        Map<String, Map<String, Object>> reprs = add.isEmpty() && remove.isEmpty() ? Map.of() : roleReprs(userId);
        List<String> missing = new ArrayList<>();
        for (String r : java.util.stream.Stream.concat(add.stream(), remove.stream()).toList())
            if (!reprs.containsKey(names.get(r))) missing.add(r + " (" + names.get(r) + ")");
        if (!missing.isEmpty()) throw ApiException.of(409, "Estos roles no existen en Keycloak: " + String.join(", ", missing));
        if (!add.isEmpty()) call("POST", mappingPath(userId), add.stream().map(r -> reprs.get(names.get(r))).toList());
        if (!remove.isEmpty()) call("DELETE", mappingPath(userId), remove.stream().map(r -> reprs.get(names.get(r))).toList());
        return List.of(add, remove);
    }

    public Map<String, Object> status() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("issuer", settings.oidcIssuer);
        info.put("realm", settings.keycloakRealm);
        info.put("client_id", settings.keycloakClientId);
        info.put("public_url", settings.keycloakPublicUrl());
        info.put("internal_url", settings.keycloakBackendUrl());
        info.put("roles_source", settings.rolesSource);
        info.put("roles_client", "client".equals(settings.rolesSource) ? settings.rolesClient() : null);
        info.put("role_names", settings.roleNames());
        info.put("management_enabled", settings.roleManagementEnabled());
        info.put("management_mode", settings.rolesAdminMode);
        info.put("management_ok", false);
        info.put("management_error", null);
        info.put("missing_roles", List.of());
        if (!settings.roleManagementEnabled()) return info;
        try {
            List<Map<String, Object>> users = getList("/users?max=1&briefRepresentation=true");
            List<String> missing = new ArrayList<>();
            if (!users.isEmpty()) {
                Map<String, Map<String, Object>> reprs = roleReprs((String) users.get(0).get("id"));
                settings.roleNames().forEach((i, e) -> { if (!reprs.containsKey(e)) missing.add(i + " (" + e + ")"); });
            }
            info.put("management_ok", true);
            info.put("missing_roles", missing);
        } catch (ApiException e) {
            info.put("management_error", e.detail);
        }
        return info;
    }
}
