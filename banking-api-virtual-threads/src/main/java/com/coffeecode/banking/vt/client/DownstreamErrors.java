package com.coffeecode.banking.vt.client;

import java.net.ConnectException;
import java.net.SocketTimeoutException;

import org.apache.hc.client5.http.ConnectTimeoutException;
import org.apache.hc.core5.http.ConnectionRequestTimeoutException;
import org.apache.hc.core5.http.NoHttpResponseException;
import org.apache.hc.core5.util.DeadlineTimeoutException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

import com.coffeecode.banking.vt.error.DownstreamException;
import com.coffeecode.banking.vt.error.ErrorCode;

/**
 * Traduce las excepciones de RestClient / Apache HttpClient 5 al catálogo {@link ErrorCode}.
 * El orden importa: {@link ConnectTimeoutException} de Apache hereda de {@link SocketTimeoutException}.
 */
final class DownstreamErrors {

    private DownstreamErrors() {
    }

    static DownstreamException map(String operation, Throwable error) {
        if (error instanceof DownstreamException alreadyMapped) {
            return alreadyMapped;
        }
        if (error instanceof RestClientResponseException response) {
            ErrorCode code = response.getStatusCode().value() == 404 ? ErrorCode.CLIENT_NOT_FOUND : ErrorCode.DOWNSTREAM_ERROR;
            return new DownstreamException(code, operation, error);
        }
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            // DeadlineTimeoutException: misma espera del pool, lanzada cuando el deadline ya venció al comprobarlo
            if (cause instanceof ConnectionRequestTimeoutException || cause instanceof DeadlineTimeoutException) {
                return new DownstreamException(ErrorCode.CONNECTION_POOL_EXHAUSTED, operation, error);
            }
            if (cause instanceof ConnectTimeoutException || cause instanceof ConnectException
                    || cause instanceof NoHttpResponseException) {
                return new DownstreamException(ErrorCode.DOWNSTREAM_UNAVAILABLE, operation, error);
            }
            if (cause instanceof SocketTimeoutException) {
                return new DownstreamException(ErrorCode.DOWNSTREAM_TIMEOUT, operation, error);
            }
        }
        if (error instanceof ResourceAccessException) {
            return new DownstreamException(ErrorCode.DOWNSTREAM_UNAVAILABLE, operation, error);
        }
        // Respuesta inválida (p. ej. JSON corrupto): el core respondió, pero mal.
        return new DownstreamException(ErrorCode.DOWNSTREAM_ERROR, operation, error);
    }
}
