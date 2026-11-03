package dbamanager.service;

import dbamanager.core.Crypto;
import dbamanager.core.Settings;
import dbamanager.model.DbConnection;
import dbamanager.model.Project;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.jboss.logging.Logger;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Revisión de la configuración al arrancar ([OK] / [AVISO] / [ERROR]), recuperación de ejecuciones
 * interrumpidas y datos demo opcionales.
 */
@Singleton
public class Startup {
    private static final Logger LOG = Logger.getLogger("dbamanager.arranque");

    @Inject Settings settings;
    @Inject Crypto crypto;
    @Inject Workflow workflow;

    void onStart(@Observes StartupEvent ev) {
        check();
        int n = QuarkusTransaction.requiringNew().call(workflow::recoverInterrupted);
        if (n > 0) LOG.warnf("[AVISO] %d solicitud(es) quedaron a medio ejecutar por un reinicio; quedaron en FAILED para revisarlas", n);
        if (settings.seedDemo) QuarkusTransaction.requiringNew().run(this::seed);
        LOG.info("[OK] DBA Manager listo");
    }

    void check() {
        try {
            crypto.checkKey();
            LOG.info("[OK] SECRETS_KEY válida");
        } catch (IllegalStateException e) {
            LOG.error("[ERROR] " + e.getMessage() + ". Genera una con: openssl rand -base64 32 | tr '+/' '-_'");
            throw e;
        }
        if (settings.metadataPassword.equals("change_me"))
            throw new IllegalStateException("[ERROR] DBAMGR_PASSWORD tiene el valor de ejemplo (change_me)");
        if (settings.keycloakPublicUrl().startsWith("http://"))
            LOG.warn("[AVISO] Keycloak por HTTP: en producción usa HTTPS");
        if (settings.audience().isEmpty()) LOG.warn("[AVISO] OIDC_AUDIENCE vacío: no se valida la audiencia de los tokens");
        if (settings.forwardedAllowIps.strip().equals("*") || settings.forwardedAllowIps.contains("0.0.0.0/0"))
            LOG.warn("[AVISO] FORWARDED_ALLOW_IPS confía en cualquier origen: la IP de la bitácora puede falsearse");
        if (settings.selfExecutionHours <= 0) LOG.warn("[AVISO] SELF_EXECUTION_HOURS=0: la ejecución activada no vence");
        if (settings.metadataRestoreReplace) LOG.warn("[AVISO] METADATA_RESTORE_REPLACE=true: restaurar puede borrar la bitácora actual");
        if (settings.appPublicUrl().isEmpty())
            LOG.warn("[AVISO] APP_PUBLIC_URL vacío: las llaves de los DBAs quedan ligadas a la dirección con la que se registren");
        else if (!settings.appPublicUrl().get().startsWith("https://") && !settings.appPublicUrl().get().contains("://localhost"))
            LOG.warn("[AVISO] APP_PUBLIC_URL no es HTTPS: los navegadores solo permiten llaves de seguridad en HTTPS o en localhost");
    }

    /** Conexiones DEV/PROD al Oracle de demo, proyectos y alcances, si la metadata está vacía. */
    void seed() {
        if (DbConnection.count() > 0) return;
        if (settings.catalogPassword().isEmpty() || settings.executorPassword().isEmpty()) {
            LOG.warn("SEED_DEMO=true requiere CATALOG_PASSWORD y EXECUTOR_PASSWORD; no se cargó la demo");
            return;
        }
        Map<String, DbConnection> conns = new HashMap<>();
        for (String[] d : List.of(new String[]{"Oracle DEV", "DEV", "DEVPDB", "Base de desarrollo (PDB local DEVPDB)"},
                new String[]{"Oracle PROD", "PROD", "PRODPDB", "Base productiva simulada (PDB local PRODPDB)"})) {
            DbConnection c = new DbConnection();
            c.name = d[0];
            c.environment = d[1];
            c.serviceName = d[2];
            c.description = d[3];
            c.host = settings.seedOracleHost;
            c.port = settings.seedOraclePort;
            c.catalogUser = "DBAMGR_CATALOG";
            c.catalogPasswordEnc = crypto.encrypt(settings.catalogPassword());
            c.execUser = "DBAMGR_EXEC";
            c.execPasswordEnc = crypto.encrypt(settings.executorPassword());
            c.allowExecution = true;
            c.createdBy = "seed";
            c.persist();
            conns.put(d[1], c);
        }
        for (String[] p : List.of(new String[]{"BI-VENTAS", "Tablero de ventas para BI", "pedro.bi", "Exposición de información de ventas al equipo de BI"},
                new String[]{"CRM-2026", "Migración CRM 2026", "ana.dev", "Nuevo módulo de clientes"})) {
            Project pr = new Project();
            pr.code = p[0];
            pr.name = p[1];
            pr.owner = p[2];
            pr.description = p[3];
            pr.persist();
        }
        Audit.system("DEMO_SEEDED", "metadata", null);
        LOG.info("Datos demo cargados (los alcances de los DBAs los firma el DBA principal desde la app)");
    }
}
