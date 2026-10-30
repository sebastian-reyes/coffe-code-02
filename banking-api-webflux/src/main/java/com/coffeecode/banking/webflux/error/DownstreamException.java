package com.coffeecode.banking.webflux.error;

/**
 * Fallo al consultar el core bancario, ya clasificado según el catálogo {@link ErrorCode}.
 */
public class DownstreamException extends RuntimeException {

    private final ErrorCode code;
    private final String operation;

    public DownstreamException(ErrorCode code, String operation, Throwable cause) {
        super(code + " en " + operation + (cause == null ? "" : ": " + cause), cause);
        this.code = code;
        this.operation = operation;
    }

    public ErrorCode code() {
        return code;
    }

    public String operation() {
        return operation;
    }
}
