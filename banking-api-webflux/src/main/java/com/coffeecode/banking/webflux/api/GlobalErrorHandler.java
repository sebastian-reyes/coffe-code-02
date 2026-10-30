package com.coffeecode.banking.webflux.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

import com.coffeecode.banking.webflux.error.ApiError;
import com.coffeecode.banking.webflux.error.DownstreamException;
import com.coffeecode.banking.webflux.error.ErrorCode;

/**
 * Traduce excepciones al cuerpo {@link ApiError}. Misma tabla de mapeo que en banking-api-virtual-threads.
 */
@RestControllerAdvice
public class GlobalErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalErrorHandler.class);

    @ExceptionHandler(DownstreamException.class)
    public ResponseEntity<ApiError> downstream(DownstreamException e, ServerHttpRequest request) {
        if (e.code() == ErrorCode.CLIENT_NOT_FOUND) {
            log.debug("{} {}", request.getPath(), e.getMessage());
        } else {
            log.warn("{} -> {} (operación={}, causa={})", request.getPath(), e.code(), e.operation(), rootCause(e));
        }
        return respond(e.code(), request);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiError> invalid(HandlerMethodValidationException e, ServerHttpRequest request) {
        return respond(ErrorCode.INVALID_CLIENT_ID, request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> unexpected(Exception e, ServerHttpRequest request) {
        log.error("{} -> error inesperado", request.getPath(), e);
        return respond(ErrorCode.INTERNAL_ERROR, request);
    }

    private static ResponseEntity<ApiError> respond(ErrorCode code, ServerHttpRequest request) {
        return ResponseEntity.status(code.status()).body(ApiError.of(code, request.getPath().value()));
    }

    private static String rootCause(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root.getClass().getName() + ": " + root.getMessage();
    }
}
