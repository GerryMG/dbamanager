package dbamanager.core;

import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** Variables de configuración de la aplicación (ver .env.example). */
@Singleton
public class Settings {

    @ConfigProperty(name = "SECRETS_KEY", defaultValue = "change_me") public String secretsKey;
    @ConfigProperty(name = "SELF_EXECUTION_HOURS", defaultValue = "8") public int selfExecutionHours;
    @ConfigProperty(name = "REAUTH_MINUTES", defaultValue = "10") public int reauthMinutes;
    @ConfigProperty(name = "PROTECTED_SCHEMAS", defaultValue = "") Optional<String> protectedSchemas;
    @ConfigProperty(name = "MAX_OPERATIONS", defaultValue = "200") public int maxOperations;
    @ConfigProperty(name = "METADATA_RESTORE_REPLACE", defaultValue = "false") public boolean metadataRestoreReplace;
    @ConfigProperty(name = "RESTORE_MAX_MB", defaultValue = "50") public int restoreMaxMb;
    @ConfigProperty(name = "FORWARDED_ALLOW_IPS", defaultValue = "127.0.0.1") public String forwardedAllowIps;
    @ConfigProperty(name = "APP_PUBLIC_URL", defaultValue = "") Optional<String> appPublicUrl;

    @ConfigProperty(name = "METADATA_USER", defaultValue = "DBAMGR") public String metadataUser;
    @ConfigProperty(name = "DBAMGR_PASSWORD", defaultValue = "change_me") public String metadataPassword;
    @ConfigProperty(name = "METADATA_DSN", defaultValue = "") Optional<String> metadataDsn;

    @ConfigProperty(name = "KEYCLOAK_PUBLIC_URL", defaultValue = "") Optional<String> keycloakPublicUrl;
    @ConfigProperty(name = "KEYCLOAK_INTERNAL_URL", defaultValue = "") Optional<String> keycloakInternalUrl;
    @ConfigProperty(name = "KEYCLOAK_REALM", defaultValue = "dbamanager") public String keycloakRealm;
    @ConfigProperty(name = "KEYCLOAK_CLIENT_ID", defaultValue = "dbamanager-web") public String keycloakClientId;
    @ConfigProperty(name = "OIDC_AUDIENCE", defaultValue = "") Optional<String> oidcAudience;
    @ConfigProperty(name = "dbamanager.oidc.server-url") public String oidcIssuer;
    @ConfigProperty(name = "KEYCLOAK_ROLES_SOURCE", defaultValue = "realm") public String rolesSource;
    @ConfigProperty(name = "KEYCLOAK_ROLES_CLIENT", defaultValue = "") Optional<String> rolesClientName;
    @ConfigProperty(name = "ROLE_REQUESTER", defaultValue = "solicitante") String roleRequester;
    @ConfigProperty(name = "ROLE_DBA", defaultValue = "dba") String roleDba;
    @ConfigProperty(name = "ROLE_DBA_ADMIN", defaultValue = "dba_admin") String roleDbaAdmin;
    @ConfigProperty(name = "ROLE_ADMIN", defaultValue = "admin") String roleAdmin;
    @ConfigProperty(name = "KEYCLOAK_ROLES_ADMIN_MODE", defaultValue = "token") public String rolesAdminMode;
    @ConfigProperty(name = "KEYCLOAK_ADMIN_CLIENT_ID", defaultValue = "") Optional<String> adminClientId;
    @ConfigProperty(name = "KEYCLOAK_ADMIN_CLIENT_SECRET", defaultValue = "") Optional<String> adminClientSecret;

    @ConfigProperty(name = "SEED_DEMO", defaultValue = "false") public boolean seedDemo;
    @ConfigProperty(name = "SEED_ORACLE_HOST", defaultValue = "localhost") public String seedOracleHost;
    @ConfigProperty(name = "SEED_ORACLE_PORT", defaultValue = "1521") public int seedOraclePort;
    @ConfigProperty(name = "CATALOG_PASSWORD", defaultValue = "") Optional<String> catalogPassword;
    @ConfigProperty(name = "EXECUTOR_PASSWORD", defaultValue = "") Optional<String> executorPassword;

    public static final String ROLE_REQUESTER_INTERNAL = "solicitante";
    public static final String DBA = "dba";
    public static final String DBA_ADMIN = "dba_admin";
    public static final String ADMIN = "admin";

    public Set<String> protectedSchemas() {
        return Arrays.stream(protectedSchemas.orElse("").split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).map(String::toUpperCase).collect(Collectors.toSet());
    }

    /** Rol interno -> nombre del rol en Keycloak. */
    public Map<String, String> roleNames() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(ROLE_REQUESTER_INTERNAL, roleRequester);
        m.put(DBA, roleDba);
        m.put(DBA_ADMIN, roleDbaAdmin);
        m.put(ADMIN, roleAdmin);
        return m;
    }

    public String rolesClient() {
        return rolesClientName.filter(s -> !s.isBlank()).orElse(keycloakClientId);
    }

    public Optional<String> audience() {
        return oidcAudience.filter(s -> !s.isBlank());
    }

    public String keycloakBackendUrl() {
        return keycloakInternalUrl.filter(s -> !s.isBlank()).or(() -> keycloakPublicUrl).orElse("").replaceAll("/+$", "");
    }

    public String keycloakPublicUrl() {
        return keycloakPublicUrl.orElse("").replaceAll("/+$", "");
    }

    public Optional<String> appPublicUrl() {
        return appPublicUrl.filter(s -> !s.isBlank()).map(s -> s.replaceAll("/+$", ""));
    }

    public boolean roleManagementEnabled() {
        return switch (rolesAdminMode) {
            case "token" -> true;
            case "service-account" -> adminClientId.filter(s -> !s.isBlank()).isPresent()
                    && adminClientSecret.filter(s -> !s.isBlank()).isPresent();
            default -> false;
        };
    }

    public String adminClientId() { return adminClientId.orElse(""); }
    public String adminClientSecret() { return adminClientSecret.orElse(""); }
    public String catalogPassword() { return catalogPassword.orElse(""); }
    public String executorPassword() { return executorPassword.orElse(""); }
    public String metadataDsn() { return metadataDsn.orElse(""); }
}
