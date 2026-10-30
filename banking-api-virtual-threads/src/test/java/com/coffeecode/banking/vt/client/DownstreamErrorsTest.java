package com.coffeecode.banking.vt.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.SocketTimeoutException;

import org.apache.hc.client5.http.ConnectTimeoutException;
import org.apache.hc.core5.http.ConnectionRequestTimeoutException;
import org.apache.hc.core5.util.Deadline;
import org.apache.hc.core5.util.DeadlineTimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

import com.coffeecode.banking.vt.error.ErrorCode;

/**
 * Casos de mapeo difíciles de provocar de forma determinista con un servidor real.
 */
class DownstreamErrorsTest {

    private static ErrorCode map(Throwable cause) {
        return DownstreamErrors.map("clients", new ResourceAccessException("I/O error", new java.io.IOException(cause))).code();
    }

    @Test
    void poolDeadlineIsPoolExhausted() {
        // Observado bajo carga: "DeadlineTimeoutException: Deadline: ..., -1 MILLISECONDS overdue"
        assertThat(map(DeadlineTimeoutException.from(Deadline.fromUnixMilliseconds(System.currentTimeMillis() - 1)))).isEqualTo(ErrorCode.CONNECTION_POOL_EXHAUSTED);
        assertThat(map(new ConnectionRequestTimeoutException("Timeout deadline: 1000 MILLISECONDS")))
                .isEqualTo(ErrorCode.CONNECTION_POOL_EXHAUSTED);
    }

    @Test
    void connectTimeoutIsUnavailableNotReadTimeout() {
        // ConnectTimeoutException de Apache hereda de SocketTimeoutException: el orden de los instanceof importa.
        assertThat(map(new ConnectTimeoutException("connect timed out"))).isEqualTo(ErrorCode.DOWNSTREAM_UNAVAILABLE);
        assertThat(map(new SocketTimeoutException("Read timed out"))).isEqualTo(ErrorCode.DOWNSTREAM_TIMEOUT);
    }
}
