package com.coffeecode.mock.chaos;

import com.coffeecode.mock.config.MockProperties;

/**
 * Escenario activo del simulador (latencia y tasas de fallo). Inmutable: se reemplaza completo.
 */
public record Scenario(long latencyMinMs, long latencyMaxMs, double errorRate, double timeoutRate, long hangMs) {

    public Scenario {
        if (latencyMinMs < 0 || latencyMaxMs < latencyMinMs) {
            throw new IllegalArgumentException("Se requiere 0 <= latencyMinMs <= latencyMaxMs");
        }
        if (errorRate < 0 || errorRate > 1 || timeoutRate < 0 || timeoutRate > 1) {
            throw new IllegalArgumentException("errorRate y timeoutRate deben estar entre 0 y 1");
        }
        if (hangMs < 0) {
            throw new IllegalArgumentException("hangMs debe ser >= 0");
        }
    }

    public static Scenario from(MockProperties properties) {
        return new Scenario(
                properties.latencyMin().toMillis(),
                properties.latencyMax().toMillis(),
                properties.errorRate(),
                properties.timeoutRate(),
                properties.hangDuration().toMillis());
    }
}
