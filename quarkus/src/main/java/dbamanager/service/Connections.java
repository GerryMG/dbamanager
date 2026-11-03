package dbamanager.service;

import dbamanager.core.ApiException;
import dbamanager.core.Crypto;
import dbamanager.core.Settings;
import dbamanager.engine.ConnInfo;
import dbamanager.engine.OracleEngine;
import dbamanager.model.DbConnection;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.HashSet;
import java.util.Set;

@ApplicationScoped
public class Connections {
    @Inject Crypto crypto;
    @Inject Settings settings;

    public DbConnection load(Long id, boolean activeOnly) {
        DbConnection c = id == null ? null : DbConnection.findById(id);
        if (c == null || (activeOnly && !c.active)) throw ApiException.of(404, "Conexión no encontrada");
        return c;
    }

    public DbConnection load(Long id) {
        return load(id, true);
    }

    /** role = "catalog" (solo diccionario) o "exec" (cuenta privilegiada). */
    public ConnInfo info(DbConnection c, String role, String appUser) {
        String user, password;
        if (role.equals("exec")) {
            if (c.execUser == null || c.execPasswordEnc == null) throw ApiException.of(409, "La conexión no tiene cuenta ejecutora configurada");
            user = c.execUser;
            password = crypto.decrypt(c.execPasswordEnc);
        } else {
            user = c.catalogUser;
            password = crypto.decrypt(c.catalogPasswordEnc);
        }
        return new ConnInfo(c.host, c.port, c.serviceName, user, password == null ? "" : password, appUser, serviceAccounts(c), c.useTls);
    }

    public Set<String> serviceAccounts(DbConnection c) {
        Set<String> s = new HashSet<>();
        s.add(c.catalogUser.toUpperCase());
        s.add(settings.metadataUser.toUpperCase());
        if (c.execUser != null) s.add(c.execUser.toUpperCase());
        return s;
    }

    /** Versión del servidor: se detecta una vez por conexión y se guarda en la metadata. */
    public String serverVersion(DbConnection c) {
        if (c.serverVersion != null) return c.serverVersion;
        String version;
        try {
            version = OracleEngine.serverVersion(info(c, "catalog", null));
        } catch (Exception e) {
            return null;  // sin conexión no se restringe por versión; se reintenta después
        }
        if (version != null) {
            Long id = c.id;
            // Transacción aparte: no debe confirmar la transacción en curso de quien llama
            QuarkusTransaction.requiringNew().run(() -> {
                DbConnection row = DbConnection.findById(id);
                if (row != null) row.serverVersion = version;
            });
            c.serverVersion = version;
        }
        return version;
    }
}
