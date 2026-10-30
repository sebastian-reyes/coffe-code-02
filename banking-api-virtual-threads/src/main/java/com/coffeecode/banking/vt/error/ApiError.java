package com.coffeecode.banking.vt.error;

/**
 * Cuerpo de error del contrato. Idéntico en ambas APIs.
 */
public record ApiError(int status, String code, String message, String path) {

    public static ApiError of(ErrorCode code, String path) {
        return new ApiError(code.status(), code.name(), code.message(), path);
    }
}
