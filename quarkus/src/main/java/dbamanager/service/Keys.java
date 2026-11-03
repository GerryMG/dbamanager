package dbamanager.service;

import com.webauthn4j.WebAuthnManager;
import com.webauthn4j.converter.AttestedCredentialDataConverter;
import com.webauthn4j.converter.util.ObjectConverter;
import com.webauthn4j.credential.CredentialRecordImpl;
import com.webauthn4j.data.AuthenticationData;
import com.webauthn4j.data.AuthenticationParameters;
import com.webauthn4j.data.AuthenticationRequest;
import com.webauthn4j.data.PublicKeyCredentialParameters;
import com.webauthn4j.data.PublicKeyCredentialType;
import com.webauthn4j.data.RegistrationData;
import com.webauthn4j.data.RegistrationParameters;
import com.webauthn4j.data.RegistrationRequest;
import com.webauthn4j.data.attestation.authenticator.AttestedCredentialData;
import com.webauthn4j.data.attestation.statement.COSEAlgorithmIdentifier;
import com.webauthn4j.data.attestation.statement.NoneAttestationStatement;
import com.webauthn4j.data.client.Origin;
import com.webauthn4j.data.client.challenge.DefaultChallenge;
import com.webauthn4j.server.ServerProperty;
import dbamanager.core.ApiException;
import dbamanager.core.Crypto;
import dbamanager.core.CurrentUser;
import dbamanager.core.Json;
import dbamanager.core.Settings;
import dbamanager.model.DbaKey;
import dbamanager.model.RootReset;
import dbamanager.model.UserSetting;
import io.vertx.core.http.HttpServerRequest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Llaves de seguridad de los DBAs (WebAuthn / FIDO2: llave USB, huella, Windows Hello, el teléfono).
 *
 * <p>La llave privada nunca sale del dispositivo del DBA. Cada decisión (aprobar, rechazar, ejecutar,
 * activar la ejecución, otorgar alcances, avalar otra llave) se firma con ella sobre un texto que
 * incluye la huella exacta del SQL y la base destino; antes de ejecutar se vuelven a verificar todas
 * las firmas. La primera llave de un DBA principal es la llave raíz; cualquier otra llave necesita el
 * aval firmado de una llave ya confiable.
 */
@ApplicationScoped
public class Keys {
    private static final Logger LOG = Logger.getLogger(Keys.class);
    /** Espera para restablecer la llave raíz. Fija a propósito: no se configura. */
    public static final Duration ROOT_RESET_WAIT = Duration.ofHours(72);
    private static final Duration CHALLENGE_TTL = Duration.ofMinutes(5);
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();

    private final ObjectConverter converter = new ObjectConverter();
    private final WebAuthnManager webauthn = WebAuthnManager.createNonStrictWebAuthnManager(converter);
    private final AttestedCredentialDataConverter credConverter = new AttestedCredentialDataConverter(converter);

    @Inject Settings settings;
    @Inject Crypto crypto;
    @Inject HttpServerRequest request;

    // ------------------------------------------------------------------ origen / RP
    /** Origen web (esquema://host[:puerto]) con el que trabaja el navegador. */
    public String origin() {
        return settings.appPublicUrl().map(u -> {
            URI uri = URI.create(u);
            return uri.getScheme() + "://" + uri.getAuthority();
        }).orElseGet(() -> request.scheme() + "://" + request.authority().toString());
    }

    public String rpId() {
        return URI.create(origin()).getHost();
    }

    // ------------------------------------------------------------------ desafíos (sin estado en el servidor)
    /**
     * Desafío firmado por el servidor (HMAC con SECRETS_KEY) que el navegador devuelve junto con la
     * respuesta de la llave. Así funciona con varias réplicas sin guardar estado.
     */
    public Map<String, Object> challenge(CurrentUser user, String purpose, Map<String, Object> statement) {
        Map<String, Object> st = new LinkedHashMap<>(statement);
        st.put("v", 1);
        st.put("kind", purpose);
        st.put("nonce", B64.encodeToString(Crypto.randomBytes(16)));
        st.put("at", OffsetDateTime.now().withNano(0).toString());
        String statementJson = Json.canonical(st);
        Map<String, Object> token = new LinkedHashMap<>();
        token.put("user", user.username());
        token.put("purpose", purpose);
        token.put("statement", statementJson);
        token.put("exp", OffsetDateTime.now().plus(CHALLENGE_TTL).toEpochSecond());
        String body = B64.encodeToString(Json.canonical(token).getBytes(StandardCharsets.UTF_8));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("token", body + "." + crypto.seal("challenge:" + body));
        out.put("challenge", B64.encodeToString(Crypto.sha256(statementJson.getBytes(StandardCharsets.UTF_8))));
        out.put("statement", Json.map(statementJson));
        out.put("rp_id", rpId());
        out.put("timeout", CHALLENGE_TTL.toMillis());
        out.put("allow_credentials", trustedKeysOf(user.username()).stream().map(k -> k.credentialId).toList());
        return out;
    }

    /** Valida el token del desafío y devuelve el texto firmado. */
    public String openChallenge(CurrentUser user, String purpose, String token) {
        if (token == null || !token.contains(".")) throw ApiException.of(422, "Falta el desafío de la llave");
        String body = token.substring(0, token.indexOf('.'));
        String mac = token.substring(token.indexOf('.') + 1);
        if (!Crypto.sameHex(mac, crypto.seal("challenge:" + body))) throw ApiException.of(422, "Desafío inválido");
        Map<String, Object> t = Json.map(new String(B64D.decode(body), StandardCharsets.UTF_8));
        if (!user.username().equals(t.get("user")) || !purpose.equals(t.get("purpose")))
            throw ApiException.of(422, "El desafío no corresponde a esta acción");
        if (((Number) t.get("exp")).longValue() < OffsetDateTime.now().toEpochSecond())
            throw ApiException.of(422, "El desafío venció; vuelve a intentarlo");
        return (String) t.get("statement");
    }

    // ------------------------------------------------------------------ registro de llaves
    public Map<String, Object> registrationOptions(CurrentUser user) {
        Map<String, Object> ch = challenge(user, "register_key", Map.of("user", user.username()));
        Map<String, Object> out = new LinkedHashMap<>(ch);
        out.put("user_id", B64.encodeToString(java.util.Arrays.copyOf(Crypto.sha256(user.username().getBytes(StandardCharsets.UTF_8)), 16)));
        out.put("user_name", user.username());
        out.put("display_name", user.fullName() == null || user.fullName().isBlank() ? user.username() : user.fullName());
        out.put("exclude_credentials", DbaKey.<DbaKey>list("username", user.username()).stream()
                .filter(k -> !"REVOKED".equals(k.status)).map(k -> k.credentialId).toList());
        return out;
    }

    /** Verifica la respuesta de registro del navegador y guarda la llave pública. */
    public DbaKey register(CurrentUser user, String token, String label, Map<String, Object> credential) {
        String statement = openChallenge(user, "register_key", token);
        Map<String, Object> response = asMap(credential.get("response"));
        byte[] clientData = b64(response.get("clientDataJSON"));
        byte[] attestation = b64(response.get("attestationObject"));
        ServerProperty sp = new ServerProperty(new Origin(origin()), rpId(), new DefaultChallenge(Crypto.sha256(statement.getBytes(StandardCharsets.UTF_8))));
        List<PublicKeyCredentialParameters> params = List.of(
                new PublicKeyCredentialParameters(PublicKeyCredentialType.PUBLIC_KEY, COSEAlgorithmIdentifier.ES256),
                new PublicKeyCredentialParameters(PublicKeyCredentialType.PUBLIC_KEY, COSEAlgorithmIdentifier.EdDSA),
                new PublicKeyCredentialParameters(PublicKeyCredentialType.PUBLIC_KEY, COSEAlgorithmIdentifier.RS256));
        RegistrationData data;
        try {
            data = webauthn.verify(new RegistrationRequest(attestation, clientData), new RegistrationParameters(sp, params, true, true));
        } catch (Exception e) {
            LOG.warnf("Registro de llave rechazado para %s: %s", user.username(), e.getMessage());
            throw ApiException.of(422, "La llave no se pudo verificar: " + e.getMessage());
        }
        AttestedCredentialData acd = data.getAttestationObject().getAuthenticatorData().getAttestedCredentialData();
        byte[] encoded = credConverter.convert(acd);
        DbaKey k = new DbaKey();
        k.username = user.username();
        k.label = label == null || label.isBlank() ? "Llave" : label.strip().substring(0, Math.min(100, label.strip().length()));
        k.credentialId = B64.encodeToString(acd.getCredentialId());
        k.credentialData = B64.encodeToString(encoded);
        k.fingerprint = fingerprintOf(acd);
        k.signCount = data.getAttestationObject().getAuthenticatorData().getSignCount();
        k.rpId = rpId();
        k.origin = origin();
        k.status = "PENDING";
        if (DbaKey.count("fingerprint", k.fingerprint) > 0) throw ApiException.of(409, "Esa llave ya está registrada");
        return k;
    }

    /** SHA-256 de la llave pública, en hex. */
    String fingerprintOf(AttestedCredentialData acd) {
        byte[] pub = converter.getCborMapper().writeValueAsBytes(acd.getCOSEKey());
        return HexFormat.of().formatHex(Crypto.sha256(pub));
    }

    public static String shortFingerprint(String fp) {
        if (fp == null) return null;
        String s = fp.substring(0, 16).toUpperCase();
        return s.substring(0, 4) + "-" + s.substring(4, 8) + "-" + s.substring(8, 12) + "-" + s.substring(12, 16);
    }

    // ------------------------------------------------------------------ firmas
    /** Firma (aserción WebAuthn) tal como se guarda en la metadata. */
    public record Signed(DbaKey key, String json) {}

    /**
     * Verifica la respuesta de la llave para el desafío `token` del propósito indicado. La llave debe
     * ser del usuario y confiable. Devuelve la firma lista para guardar.
     */
    public Signed sign(CurrentUser user, String purpose, String token, Map<String, Object> assertion) {
        String statement = openChallenge(user, purpose, token);
        return signStatement(user, statement, assertion, true);
    }

    /** Igual que sign, sin exigir que la llave ya sea confiable (para avalar la primera vez la propia). */
    Signed signStatement(CurrentUser user, String statement, Map<String, Object> assertion, boolean requireTrusted) {
        if (assertion == null) throw ApiException.of(422, "Falta la firma de tu llave de seguridad");
        String credId = (String) assertion.get("id");
        DbaKey key = DbaKey.find("credentialId", credId).firstResult();
        if (key == null || !key.username.equals(user.username()))
            throw ApiException.of(403, "Esa llave no está registrada a tu nombre en DBA Manager");
        if (requireTrusted && !trusted(key))
            throw ApiException.of(403, "Tu llave aún no es confiable (está pendiente de aval o fue revocada)");
        Map<String, Object> response = asMap(assertion.get("response"));
        Map<String, Object> sig = new LinkedHashMap<>();
        sig.put("credential_id", credId);
        sig.put("authenticator_data", response.get("authenticatorData"));
        sig.put("client_data_json", response.get("clientDataJSON"));
        sig.put("signature", response.get("signature"));
        sig.put("user_handle", response.get("userHandle"));
        sig.put("statement", statement);
        long count = verify(key, sig, key.signCount);
        if (count > key.signCount) key.signCount = count;
        key.lastUsedAt = OffsetDateTime.now();
        return new Signed(key, Json.write(sig));
    }

    /** Verifica una firma guardada; devuelve el contador de la llave o lanza si no es válida. */
    long verify(DbaKey key, Map<String, Object> sig, long counter) {
        String statement = (String) sig.get("statement");
        AttestedCredentialData acd = credConverter.convert(B64D.decode(key.credentialData));
        CredentialRecordImpl record = new CredentialRecordImpl(new NoneAttestationStatement(), null, null, null, counter, acd, null, null, null, null);
        ServerProperty sp = new ServerProperty(new Origin(key.origin), key.rpId, new DefaultChallenge(Crypto.sha256(statement.getBytes(StandardCharsets.UTF_8))));
        AuthenticationRequest req = new AuthenticationRequest(B64D.decode((String) sig.get("credential_id")),
                sig.get("user_handle") == null ? null : b64(sig.get("user_handle")),
                b64(sig.get("authenticator_data")), b64(sig.get("client_data_json")), null, b64(sig.get("signature")));
        try {
            AuthenticationData data = webauthn.verify(req, new AuthenticationParameters(sp, record, null, true, true));
            return data.getAuthenticatorData().getSignCount();
        } catch (Exception e) {
            throw ApiException.of(403, "La firma de la llave no es válida: " + e.getMessage());
        }
    }

    /** ¿La firma guardada es válida, de una llave confiable del usuario indicado, sobre ese tipo de texto? */
    public boolean validSignature(DbaKey key, String sigJson, String username, Map<String, Object> expected) {
        if (key == null || sigJson == null || !Objects.equals(key.username, username) || !trusted(key)) return false;
        try {
            Map<String, Object> sig = Json.map(sigJson);
            Map<String, Object> st = Json.map((String) sig.get("statement"));
            for (Map.Entry<String, Object> e : expected.entrySet()) {
                if (!Objects.equals(String.valueOf(st.get(e.getKey())), String.valueOf(e.getValue()))) return false;
            }
            if (!Objects.equals(sig.get("credential_id"), key.credentialId)) return false;
            verify(key, sig, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Texto firmado (statement) de una firma guardada. */
    public static Map<String, Object> statementOf(String sigJson) {
        if (sigJson == null) return null;
        return Json.map((String) Json.map(sigJson).get("statement"));
    }

    // ------------------------------------------------------------------ confianza
    /** Llave activa con cadena de avales válida hasta la llave raíz vigente. */
    public boolean trusted(DbaKey key) {
        return trusted(key, new HashSet<>());
    }

    private boolean trusted(DbaKey key, Set<Long> visited) {
        if (key == null || !"ACTIVE".equals(key.status) || !visited.add(key.id)) return false;
        if (key.root) return key.id.equals(currentRootId());
        DbaKey endorser = key.endorsedBy;
        if (endorser == null || key.endorsement == null || !trusted(endorser, visited)) return false;
        try {
            Map<String, Object> sig = Json.map(key.endorsement);
            Map<String, Object> st = Json.map((String) sig.get("statement"));
            if (!"endorse_key".equals(st.get("kind")) || !key.fingerprint.equals(st.get("fingerprint"))
                    || !key.username.equals(st.get("key_owner")) || !endorser.username.equals(st.get("endorser"))
                    || !endorser.credentialId.equals(sig.get("credential_id"))) return false;
            verify(endorser, sig, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Llave raíz vigente: la raíz activa más reciente, creada después del último restablecimiento. */
    public Long currentRootId() {
        OffsetDateTime resetAt = lastResetDone();
        DbaKey root = DbaKey.find("root = ?1 and status = 'ACTIVE' order by createdAt desc, id desc", true).firstResult();
        if (root == null || (resetAt != null && root.createdAt.isBefore(resetAt))) return null;
        return root.id;
    }

    OffsetDateTime lastResetDone() {
        RootReset r = RootReset.find("status = 'DONE' order by closedAt desc").firstResult();
        return r == null ? null : r.closedAt;
    }

    /** Se puede crear una llave raíz nueva: nunca hubo una, o se completó un restablecimiento después de la última. */
    public boolean rootSlotOpen() {
        applyDueResets();
        DbaKey last = DbaKey.find("root = ?1 order by createdAt desc, id desc", true).firstResult();
        if (last == null) return true;
        OffsetDateTime resetAt = lastResetDone();
        return resetAt != null && last.createdAt.isBefore(resetAt);
    }

    public List<DbaKey> trustedKeysOf(String username) {
        return DbaKey.<DbaKey>list("username = ?1 and status = 'ACTIVE'", username).stream().filter(this::trusted).toList();
    }

    public boolean hasTrustedKey(String username) {
        return !trustedKeysOf(username).isEmpty();
    }

    // ------------------------------------------------------------------ restablecimiento de la raíz
    public RootReset activeReset() {
        applyDueResets();
        return RootReset.find("status = 'WAITING' order by requestedAt desc").firstResult();
    }

    /** Cumplido el plazo sin cancelación: se revocan todas las llaves y lo pendiente pierde sus firmas. */
    public void applyDueResets() {
        for (RootReset r : RootReset.<RootReset>list("status = 'WAITING'")) {
            if (r.effectiveAt.isAfter(OffsetDateTime.now())) continue;
            r.status = "DONE";
            r.closedAt = OffsetDateTime.now();
            r.closedBy = "sistema";
            for (DbaKey k : DbaKey.<DbaKey>list("status <> 'REVOKED'")) {
                k.status = "REVOKED";
                k.revokedAt = r.closedAt;
                k.revokedBy = "restablecimiento #" + r.id;
            }
            for (UserSetting s : UserSetting.<UserSetting>listAll()) {
                s.canExecute = false;
                s.canExecuteUntil = null;
            }
            Audit.system("ROOT_RESET_DONE", "root_reset", r.id, "requested_by", r.requestedBy);
        }
    }

    // ------------------------------------------------------------------ vista
    public Map<String, Object> view(DbaKey k) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", k.id);
        m.put("username", k.username);
        m.put("label", k.label);
        m.put("fingerprint", shortFingerprint(k.fingerprint));
        m.put("status", k.status);
        m.put("root", k.root);
        m.put("trusted", trusted(k));
        m.put("endorsed_by", k.endorsedByUser);
        m.put("created_at", k.createdAt);
        m.put("activated_at", k.activatedAt);
        m.put("last_used_at", k.lastUsedAt);
        m.put("revoked_at", k.revokedAt);
        m.put("revoked_by", k.revokedBy);
        m.put("origin", k.origin);
        return m;
    }

    public List<Map<String, Object>> views(List<DbaKey> keys) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (DbaKey k : keys) out.add(view(k));
        return out;
    }

    // ------------------------------------------------------------------ utilidades
    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        if (!(o instanceof Map)) throw ApiException.of(422, "Respuesta de la llave incompleta");
        return (Map<String, Object>) o;
    }

    private static byte[] b64(Object v) {
        if (v == null) throw ApiException.of(422, "Respuesta de la llave incompleta");
        return B64D.decode(v.toString());
    }
}
