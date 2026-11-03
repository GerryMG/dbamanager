package dbamanager.api;

import dbamanager.core.ApiException;
import dbamanager.core.CurrentUser;
import dbamanager.core.Settings;
import dbamanager.model.DbaKey;
import dbamanager.model.RootReset;
import dbamanager.model.UserSetting;
import dbamanager.service.Audit;
import dbamanager.service.Keys;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Llaves de seguridad de los DBAs: registro, aval, revocación y restablecimiento de la raíz. */
@Path("/api/keys")
public class KeysResource {
    @Inject CurrentUser user;
    @Inject Keys keys;
    @Inject Settings settings;

    static Map<String, Object> resetView(RootReset r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.id);
        m.put("requested_by", r.requestedBy);
        m.put("reason", r.reason);
        m.put("requested_at", r.requestedAt);
        m.put("effective_at", r.effectiveAt);
        m.put("status", r.status);
        return m;
    }

    @GET
    @Transactional
    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        Long rootId = keys.currentRootId();
        DbaKey root = rootId == null ? null : DbaKey.findById(rootId);
        m.put("root", root == null ? null : Map.of("username", root.username, "fingerprint", Keys.shortFingerprint(root.fingerprint),
                "created_at", root.createdAt));
        m.put("root_slot_open", keys.rootSlotOpen());
        RootReset reset = keys.activeReset();
        m.put("reset", reset == null ? null : resetView(reset));
        m.put("mine", keys.views(DbaKey.list("username = ?1 order by createdAt desc", user.username())));
        m.put("pending", user.isDbaAdmin()
                ? keys.views(DbaKey.<DbaKey>list("status = 'PENDING' and username <> ?1 order by createdAt", user.username())) : List.of());
        m.put("all", user.isDba() || user.isAdmin() ? keys.views(DbaKey.list("order by username, createdAt desc")) : List.of());
        m.put("rp_id", keys.rpId());
        m.put("origin", keys.origin());
        m.put("reauth_ok", recentReauth());
        m.put("reauth_minutes", settings.reauthMinutes);
        m.put("can_register", user.isDba());
        return m;
    }

    private boolean recentReauth() {
        UserSetting s = UserSetting.findById(user.username());
        return s != null && s.reauthAt != null && s.reauthAt.isAfter(OffsetDateTime.now().minusMinutes(settings.reauthMinutes));
    }

    /** Opciones para registrar una llave nueva (exige haber escrito la contraseña hace poco). */
    @POST
    @Path("/register/options")
    @Transactional
    public Map<String, Object> registerOptions() {
        user.requireDba();
        if (!recentReauth())
            throw ApiException.coded(401, "REAUTH_REQUIRED", "Por seguridad, vuelve a escribir tu contraseña antes de registrar una llave");
        return keys.registrationOptions(user);
    }

    @POST
    @Path("/register")
    @Transactional
    public Map<String, Object> register(Map<String, Object> body) {
        user.requireDba();
        if (!recentReauth())
            throw ApiException.coded(401, "REAUTH_REQUIRED", "Por seguridad, vuelve a escribir tu contraseña antes de registrar una llave");
        DbaKey k = keys.register(user, Body.str(body, "token"), Body.str(body, "label"), Body.map(body, "credential"));
        if (user.isDbaAdmin() && keys.rootSlotOpen()) {
            k.root = true;
            k.status = "ACTIVE";
            k.activatedAt = OffsetDateTime.now();
            k.persist();
            Audit.log(user, "KEY_ROOT_CREATED", "key", k.id, "fingerprint", Keys.shortFingerprint(k.fingerprint), "label", k.label);
        } else {
            k.persist();
            Audit.log(user, "KEY_REGISTERED", "key", k.id, "fingerprint", Keys.shortFingerprint(k.fingerprint), "label", k.label);
        }
        // Se cierra la ventana de reautenticación: cada llave nueva exige escribir la contraseña de nuevo
        UserSetting s = UserSetting.findById(user.username());
        s.reauthAt = null;
        return keys.view(k);
    }

    private static DbaKey load(Long id) {
        DbaKey k = DbaKey.findById(id);
        if (k == null) throw ApiException.of(404, "Llave no encontrada");
        return k;
    }

    /** Desafío para firmar una acción sobre llaves: avalar, revocar o cancelar un restablecimiento. */
    @POST
    @Path("/challenge")
    @Transactional
    public Map<String, Object> challenge(Map<String, Object> body) {
        user.requireDba();
        String purpose = Body.required(body, "purpose", "El propósito");
        Map<String, Object> st = new LinkedHashMap<>();
        switch (purpose) {
            case "endorse_key" -> {
                DbaKey k = load(Body.lng(body, "key_id"));
                checkEndorse(k);
                st.put("endorser", user.username());
                st.put("key_owner", k.username);
                st.put("fingerprint", k.fingerprint);
                st.put("label", k.label);
            }
            case "revoke_key" -> {
                DbaKey k = load(Body.lng(body, "key_id"));
                checkRevoke(k);
                st.put("by", user.username());
                st.put("key_owner", k.username);
                st.put("fingerprint", k.fingerprint);
            }
            case "cancel_root_reset" -> {
                user.requireDbaAdmin();
                RootReset r = keys.activeReset();
                if (r == null) throw ApiException.of(404, "No hay un restablecimiento en espera");
                st.put("reset", r.id);
                st.put("by", user.username());
            }
            case "self_execution" -> {
                st.put("user", user.username());
                st.put("enabled", true);
            }
            default -> throw ApiException.of(422, "Propósito inválido");
        }
        return keys.challenge(user, purpose, st);
    }

    private void checkEndorse(DbaKey k) {
        if (!"PENDING".equals(k.status)) throw ApiException.of(409, "La llave no está pendiente de aval");
        if (!k.username.equals(user.username()) && !user.isDbaAdmin())
            throw ApiException.of(403, "Solo un DBA principal puede avalar llaves de otras personas");
        if (!keys.hasTrustedKey(user.username()))
            throw ApiException.coded(403, "KEY_REQUIRED", "Necesitas una llave confiable para avalar otra");
    }

    private void checkRevoke(DbaKey k) {
        if ("REVOKED".equals(k.status)) throw ApiException.of(409, "La llave ya está revocada");
        if (k.root) throw ApiException.of(409, "La llave raíz no se revoca; si se perdió, usa el restablecimiento de la raíz");
        if (!k.username.equals(user.username()) && !user.isDbaAdmin())
            throw ApiException.of(403, "Solo el dueño o un DBA principal pueden revocar una llave");
    }

    private Keys.Signed signed(String purpose, Map<String, Object> body, Map<String, Object> expected) {
        Keys.Signed s = keys.sign(user, purpose, Body.str(body, "token"), Body.map(body, "assertion"));
        Map<String, Object> st = Keys.statementOf(s.json());
        for (Map.Entry<String, Object> e : expected.entrySet()) {
            if (!Objects.equals(String.valueOf(st.get(e.getKey())), String.valueOf(e.getValue())))
                throw ApiException.of(409, "Lo firmado no corresponde a esta acción; vuelve a intentarlo");
        }
        return s;
    }

    @POST
    @Path("/{id}/endorse")
    @Transactional
    public Map<String, Object> endorse(@PathParam("id") Long id, Map<String, Object> body) {
        user.requireDba();
        DbaKey k = load(id);
        checkEndorse(k);
        Keys.Signed s = signed("endorse_key", body, Map.of("endorser", user.username(), "key_owner", k.username, "fingerprint", k.fingerprint));
        k.status = "ACTIVE";
        k.endorsedBy = s.key();
        k.endorsedByUser = user.username();
        k.endorsement = s.json();
        k.activatedAt = OffsetDateTime.now();
        Audit.log(user, "KEY_ENDORSED", "key", k.id, "owner", k.username, "fingerprint", Keys.shortFingerprint(k.fingerprint),
                "endorser_key", Keys.shortFingerprint(s.key().fingerprint));
        return keys.view(k);
    }

    @POST
    @Path("/{id}/revoke")
    @Transactional
    public Map<String, Object> revoke(@PathParam("id") Long id, Map<String, Object> body) {
        user.requireDba();
        DbaKey k = load(id);
        checkRevoke(k);
        if (!k.username.equals(user.username()) || "ACTIVE".equals(k.status) && keys.hasTrustedKey(user.username())) {
            // Revocar una llave de otro, o una propia activa teniendo otra confiable, se firma.
            signed("revoke_key", body, Map.of("by", user.username(), "key_owner", k.username, "fingerprint", k.fingerprint));
        }
        k.status = "REVOKED";
        k.revokedAt = OffsetDateTime.now();
        k.revokedBy = user.username();
        Audit.log(user, "KEY_REVOKED", "key", k.id, "owner", k.username, "fingerprint", Keys.shortFingerprint(k.fingerprint));
        return keys.view(k);
    }

    /** Pide restablecer la llave raíz (si el DBA principal perdió todas sus llaves). */
    @POST
    @Path("/root-reset")
    @Transactional
    public Map<String, Object> requestReset(Map<String, Object> body) {
        if (!user.isAdmin() && !user.isDbaAdmin()) throw ApiException.of(403, "Requiere rol admin o dba_admin");
        String reason = Body.required(body, "reason", "El motivo");
        if (reason.length() < 10 || reason.length() > 2000) throw ApiException.of(422, "El motivo debe tener entre 10 y 2000 caracteres");
        if (keys.activeReset() != null) throw ApiException.of(409, "Ya hay un restablecimiento en espera");
        if (keys.currentRootId() == null && keys.rootSlotOpen()) throw ApiException.of(409, "No hay llave raíz que restablecer");
        RootReset r = new RootReset();
        r.requestedBy = user.username();
        r.reason = reason;
        r.effectiveAt = OffsetDateTime.now().plus(Keys.ROOT_RESET_WAIT);
        r.status = "WAITING";
        r.persist();
        Audit.log(user, "ROOT_RESET_REQUESTED", "root_reset", r.id, "reason", reason, "effective_at", r.effectiveAt);
        return resetView(r);
    }

    @POST
    @Path("/root-reset/cancel")
    @Transactional
    public Map<String, Object> cancelReset(Map<String, Object> body) {
        user.requireDbaAdmin();
        RootReset r = keys.activeReset();
        if (r == null) throw ApiException.of(404, "No hay un restablecimiento en espera");
        signed("cancel_root_reset", body, Map.of("reset", r.id, "by", user.username()));
        r.status = "CANCELLED";
        r.closedAt = OffsetDateTime.now();
        r.closedBy = user.username();
        Audit.log(user, "ROOT_RESET_CANCELLED", "root_reset", r.id);
        return resetView(r);
    }
}
