package com.coffeecode.banking.vt.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

import com.coffeecode.banking.vt.error.ApiError;
import com.coffeecode.banking.vt.error.DownstreamException;
import com.coffeecode.banking.vt.error.ErrorCode;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Traduce excepciones al cuerpo {@link ApiError}. Misma tabla de mapeo que en banking-api-webflux.
 */
@RestControllerAdvice
public class GlobalErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalErrorHandler.class);

    @ExceptionHandler(DownstreamException.class)
    public ResponseEntity<ApiError> downstream(DownstreamException e, HttpServletRequest request) {
        if (e.code() == ErrorCode.CLIENT_NOT_FOUND) {
            log.debug("{} {}", request.getRequestURI(), e.getMessage());
        } else {
            log.warn("{} -> {} (operación={}, causa={})", request.getRequestURI(), e.code(), e.operation(), rootCause(e));
        }
        return respond(e.code(), request);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiError> invalid(HandlerMethodValidationException e, HttpServletRequest request) {
        return respond(ErrorCode.INVALID_CLIENT_ID, request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> unexpected(Exception e, HttpServletRequest request) {
        log.error("{} -> error inesperado", request.getRequestURI(), e);
        return respond(ErrorCode.INTERNAL_ERROR, request);
    }

    private static ResponseEntity<ApiError> respond(ErrorCode code, HttpServletRequest request) {
        return ResponseEntity.status(code.status()).body(ApiError.of(code, request.getRequestURI()));
    }

    private static String rootCause(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root.getClass().getName() + ": " + root.getMessage();
    }
}
