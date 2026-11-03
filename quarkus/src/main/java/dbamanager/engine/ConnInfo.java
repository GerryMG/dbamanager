package dbamanager.engine;

import java.util.Set;

/** Datos para abrir una sesión contra una base administrada. */
public record ConnInfo(String host, int port, String serviceName, String user, String password,
                       String appUser, Set<String> excludedSchemas, boolean tls) {

    public String jdbcUrl() {
        return "jdbc:oracle:thin:@" + (tls ? "tcps://" : "") + host + ":" + port + "/" + serviceName;
    }
}
