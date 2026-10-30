package com.coffeecode.banking.webflux.client;

import java.net.ConnectException;
import java.util.concurrent.TimeoutException;

import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import com.coffeecode.banking.webflux.error.DownstreamException;
import com.coffeecode.banking.webflux.error.ErrorCode;

import io.netty.handler.timeout.ReadTimeoutException;
import reactor.netty.http.client.PrematureCloseException;
import reactor.netty.internal.shaded.reactor.pool.PoolAcquirePendingLimitException;
import reactor.netty.internal.shaded.reactor.pool.PoolAcquireTimeoutException;

/**
 * Traduce las excepciones de WebClient / Reactor Netty al catálogo {@link ErrorCode}.
 * El orden importa: {@link PoolAcquireTimeoutException} hereda de {@link TimeoutException}.
 */
final class DownstreamErrors {

    private DownstreamErrors() {
    }

    static DownstreamException map(String operation, Throwable error) {
        if (error instanceof DownstreamException alreadyMapped) {
            return alreadyMapped;
        }
        if (error instanceof WebClientResponseException response) {
            ErrorCode code = response.getStatusCode().value() == 404 ? ErrorCode.CLIENT_NOT_FOUND : ErrorCode.DOWNSTREAM_ERROR;
            return new DownstreamException(code, operation, error);
        }
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof PoolAcquireTimeoutException || cause instanceof PoolAcquirePendingLimitException) {
                return new DownstreamException(ErrorCode.CONNECTION_POOL_EXHAUSTED, operation, error);
            }
            if (cause instanceof ReadTimeoutException || cause instanceof TimeoutException) {
                return new DownstreamException(ErrorCode.DOWNSTREAM_TIMEOUT, operation, error);
            }
            // io.netty.channel.ConnectTimeoutException también es un ConnectException
            if (cause instanceof ConnectException || cause instanceof PrematureCloseException) {
                return new DownstreamException(ErrorCode.DOWNSTREAM_UNAVAILABLE, operation, error);
            }
        }
        if (error instanceof WebClientRequestException) {
            return new DownstreamException(ErrorCode.DOWNSTREAM_UNAVAILABLE, operation, error);
        }
        // Respuesta inválida (p. ej. JSON corrupto): el core respondió, pero mal.
        return new DownstreamException(ErrorCode.DOWNSTREAM_ERROR, operation, error);
    }
}
