package dbamanager.engine;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Resultado de la validación previa: error | warning | info. */
public record Finding(@JsonProperty("level") String level,
                      @JsonProperty("message") String message,
                      @JsonProperty("op_seq") Integer opSeq) {}
