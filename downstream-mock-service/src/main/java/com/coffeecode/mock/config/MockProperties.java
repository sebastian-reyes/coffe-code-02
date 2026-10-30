package com.coffeecode.mock.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Escenario inicial del simulador. Puede cambiarse en caliente con {@code PUT /mock/admin/scenario}
 * si {@code mock.admin-enabled=true}.
 *
 * @param latencyMin   latencia mínima de cada endpoint
 * @param latencyMax   latencia máxima de cada endpoint (distribución uniforme entre min y max)
 * @param errorRate    probabilidad [0..1] de responder 503
 * @param timeoutRate  probabilidad [0..1] de "colgarse" durante {@code hangDuration}
 * @param hangDuration cuánto tarda una respuesta colgada (debe superar el timeout de las APIs)
 * @param adminEnabled habilita los endpoints de administración del escenario
 */
@ConfigurationProperties("mock")
public record MockProperties(
        @DefaultValue("100ms") Duration latencyMin,
        @DefaultValue("800ms") Duration latencyMax,
        @DefaultValue("0.0") double errorRate,
        @DefaultValue("0.0") double timeoutRate,
        @DefaultValue("30s") Duration hangDuration,
        @DefaultValue("true") boolean adminEnabled) {
}
