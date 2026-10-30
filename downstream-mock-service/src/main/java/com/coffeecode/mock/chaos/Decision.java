package com.coffeecode.mock.chaos;

import java.time.Duration;

/**
 * Qué hará el simulador con una petición concreta: cuánto esperar y si debe fallar.
 *
 * @param delay  demora antes de responder (no bloqueante: se aplica con Mono.delay)
 * @param fail   si es true responde 5xx en lugar del dato
 * @param status código HTTP de error cuando {@code fail} es true
 * @param reason motivo, útil en logs
 */
public record Decision(Duration delay, boolean fail, int status, String reason) {

    static Decision ok(Duration delay, String reason) {
        return new Decision(delay, false, 200, reason);
    }

    static Decision error(Duration delay, int status, String reason) {
        return new Decision(delay, true, status, reason);
    }
}
