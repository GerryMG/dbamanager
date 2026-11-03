package dbamanager.engine;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/** Sentencia generada. En scripts, las PL/SQL terminan con "/" en vez de ";". */
public record Stmt(@JsonProperty("sql") String sql,
                   @JsonProperty("plsql") boolean plsql,
                   @JsonProperty("secret_placeholder") String secretPlaceholder) {

    public Stmt(String sql) {
        this(sql, false, null);
    }

    public static Stmt plsql(String sql) {
        return new Stmt(sql, true, null);
    }

    public static Stmt of(Map<String, Object> m) {
        return new Stmt((String) m.get("sql"), Boolean.TRUE.equals(m.get("plsql")), (String) m.get("secret_placeholder"));
    }

    public String scriptText() {
        String s = sql.stripTrailing();
        return plsql ? s + "\n/" : s + ";";
    }
}
