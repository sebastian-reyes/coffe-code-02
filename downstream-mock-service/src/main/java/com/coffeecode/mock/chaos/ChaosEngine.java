package com.coffeecode.mock.chaos;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.coffeecode.mock.config.MockProperties;

/**
 * Decide la latencia y el resultado de cada petición.
 *
 * <p>Hay dos mecanismos, pensados para no contaminar accidentalmente las pruebas normales:
 * <ol>
 *   <li><b>IDs mágicos deterministas</b> (útiles para la demo en vivo y para tests). Los scripts
 *       de carga nunca los usan: generan IDs entre C1000 y C8999.
 *       <ul>
 *         <li>{@code C0404}: cliente inexistente (404 en todos los endpoints)</li>
 *         <li>{@code C0500}: el endpoint de saldo responde 500</li>
 *         <li>{@code C0504}: el endpoint de saldo se cuelga (timeout en las APIs)</li>
 *         <li>{@code C0599}: el endpoint de movimientos responde 500 (degradación parcial)</li>
 *         <li>{@code C0800}: latencia elevada (1500 ms) en todos los endpoints, sin superar el timeout</li>
 *       </ul></li>
 *   <li><b>Escenario global probabilístico</b> ({@link Scenario}), configurable por YAML o en caliente
 *       con {@code PUT /mock/admin/scenario}. Por defecto: 100-800 ms, sin errores ni timeouts.</li>
 * </ol>
 */
@Component
public class ChaosEngine {

    public static final String NOT_FOUND_ID = "C0404";
    public static final String BALANCE_ERROR_ID = "C0500";
    public static final String BALANCE_TIMEOUT_ID = "C0504";
    public static final String MOVEMENTS_ERROR_ID = "C0599";
    public static final String HIGH_LATENCY_ID = "C0800";

    static final Duration HIGH_LATENCY = Duration.ofMillis(1500);

    private static final Logger log = LoggerFactory.getLogger(ChaosEngine.class);

    private final Scenario initial;
    private final AtomicReference<Scenario> current;

    public ChaosEngine(MockProperties properties) {
        this.initial = Scenario.from(properties);
        this.current = new AtomicReference<>(initial);
        log.info("Escenario inicial del mock: {}", initial);
    }

    public Scenario current() {
        return current.get();
    }

    public Scenario update(Scenario scenario) {
        current.set(scenario);
        log.warn("Escenario del mock actualizado: {}", scenario);
        return scenario;
    }

    public Scenario reset() {
        return update(initial);
    }

    public Decision decide(Endpoint endpoint, String clientId) {
        Scenario scenario = current.get();
        Duration hang = Duration.ofMillis(scenario.hangMs());

        // 1) IDs mágicos: siempre el mismo comportamiento, independientemente del escenario.
        switch (clientId) {
            case BALANCE_ERROR_ID -> {
                if (endpoint == Endpoint.BALANCES) {
                    return Decision.error(randomLatency(scenario), 500, "magic-id: balance 500");
                }
            }
            case BALANCE_TIMEOUT_ID -> {
                if (endpoint == Endpoint.BALANCES) {
                    return Decision.ok(hang, "magic-id: balance hang");
                }
            }
            case MOVEMENTS_ERROR_ID -> {
                if (endpoint == Endpoint.MOVEMENTS) {
                    return Decision.error(randomLatency(scenario), 500, "magic-id: movements 500");
                }
            }
            case HIGH_LATENCY_ID -> {
                return Decision.ok(HIGH_LATENCY, "magic-id: high latency");
            }
            default -> {
                // continúa con el escenario global
            }
        }

        // 2) Escenario global probabilístico.
        ThreadLocalRandom random = ThreadLocalRandom.current();
        if (scenario.timeoutRate() > 0 && random.nextDouble() < scenario.timeoutRate()) {
            return Decision.ok(hang, "scenario: hang");
        }
        if (scenario.errorRate() > 0 && random.nextDouble() < scenario.errorRate()) {
            return Decision.error(randomLatency(scenario), 503, "scenario: error");
        }
        return Decision.ok(randomLatency(scenario), "scenario: ok");
    }

    private static Duration randomLatency(Scenario scenario) {
        long min = scenario.latencyMinMs();
        long max = scenario.latencyMaxMs();
        long millis = min == max ? min : ThreadLocalRandom.current().nextLong(min, max + 1);
        return Duration.ofMillis(millis);
    }
}
