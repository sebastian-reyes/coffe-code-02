package com.coffeecode.banking.vt.error;

/**
 * Catálogo de errores del contrato. Idéntico en ambas APIs.
 */
public enum ErrorCode {

    INVALID_CLIENT_ID(400, "El clientId debe tener el formato C#### (ej. C0001)"),
    CLIENT_NOT_FOUND(404, "El cliente no existe en el core bancario"),
    INTERNAL_ERROR(500, "Error interno del servicio"),
    DOWNSTREAM_ERROR(502, "El core bancario respondió con un error"),
    DOWNSTREAM_UNAVAILABLE(503, "No fue posible conectar con el core bancario"),
    CONNECTION_POOL_EXHAUSTED(503, "No hay conexiones disponibles hacia el core bancario"),
    DOWNSTREAM_TIMEOUT(504, "El core bancario no respondió a tiempo");

    private final int status;
    private final String message;

    ErrorCode(int status, String message) {
        this.status = status;
        this.message = message;
    }

    public int status() {
        return status;
    }

    public String message() {
        return message;
    }
}
