package dbamanager.service;

import dbamanager.core.CurrentUser;
import dbamanager.core.Json;
import dbamanager.model.AuditLog;

import java.util.LinkedHashMap;
import java.util.Map;

/** Bitácora. El registro se confirma junto con el cambio (misma transacción). */
public final class Audit {
    private Audit() {}

    public static void log(CurrentUser user, String action, String entityType, Object entityId, Object... details) {
        write(user.username(), user.ip(), action, entityType, entityId, details);
    }

    public static void system(String action, String entityType, Object entityId, Object... details) {
        write("sistema", null, action, entityType, entityId, details);
    }

    public static void write(String username, String ip, String action, String entityType, Object entityId, Object... details) {
        AuditLog a = new AuditLog();
        a.username = username;
        a.ip = ip;
        a.action = action;
        a.entityType = entityType;
        a.entityId = entityId == null ? null : entityId.toString();
        if (details.length > 0) {
            Map<String, Object> d = new LinkedHashMap<>();
            for (int i = 0; i + 1 < details.length; i += 2) d.put((String) details[i], details[i + 1]);
            a.details = Json.write(d);
        }
        a.persist();
    }
}
