package dbamanager.core;

import io.smallrye.config.ConfigSourceContext;
import io.smallrye.config.ConfigSourceFactory;
import io.smallrye.config.ConfigValue;
import io.smallrye.config.PropertiesConfigSource;
import org.eclipse.microprofile.config.spi.ConfigSource;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Propiedades que se calculan a partir de las variables de .env.example, para no pedir más
 * secretos de los necesarios: la URL del proveedor OIDC y las llaves de la sesión (derivadas de
 * SECRETS_KEY).
 */
public class DerivedConfig implements ConfigSourceFactory {

    @Override
    public Iterable<ConfigSource> getConfigSources(ConfigSourceContext ctx) {
        Map<String, String> out = new HashMap<>();
        String issuer = value(ctx, "OIDC_ISSUER_URL");
        if (issuer.isEmpty()) {
            String base = value(ctx, "KEYCLOAK_INTERNAL_URL");
            if (base.isEmpty()) base = value(ctx, "KEYCLOAK_PUBLIC_URL");
            base = base.replaceAll("/+$", "");
            String realm = value(ctx, "KEYCLOAK_REALM");
            issuer = base + "/realms/" + (realm.isEmpty() ? "dbamanager" : realm);
        }
        out.put("dbamanager.oidc.server-url", issuer);

        String key = value(ctx, "SECRETS_KEY");
        if (!key.isEmpty()) {
            for (String tenant : List.of("", "reauth.")) {
                out.put("quarkus.oidc." + tenant + "token-state-manager.encryption-secret", derive(key, "session" + tenant));
                out.put("quarkus.oidc." + tenant + "authentication.state-secret", derive(key, "state" + tenant));
            }
        }
        return List.of(new PropertiesConfigSource(out, "dbamanager-derived", 150));
    }

    private static String value(ConfigSourceContext ctx, String name) {
        ConfigValue v = ctx.getValue(name);
        return v == null || v.getValue() == null ? "" : v.getValue().trim();
    }

    static String derive(String secret, String purpose) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(("dbamanager:" + purpose).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
