package dbamanager.core;

import java.util.List;
import java.util.Map;

/** Error de la API: se responde como {"detail": ...} con el código HTTP indicado. */
public class ApiException extends RuntimeException {
    public final int status;
    public final transient Object detail;

    public ApiException(int status, Object detail) {
        super(detail instanceof String s ? s : String.valueOf(detail));
        this.status = status;
        this.detail = detail;
    }

    public static ApiException of(int status, String message) {
        return new ApiException(status, message);
    }

    /** 422 con la lista de errores por operación ({op_seq, field, message}). */
    public static ApiException invalid(String message, List<Map<String, Object>> errors) {
        return new ApiException(422, Map.of("message", message, "errors", errors));
    }

    public static ApiException coded(int status, String code, String message) {
        return new ApiException(status, Map.of("code", code, "message", message));
    }
}
