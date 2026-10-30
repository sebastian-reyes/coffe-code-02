package com.coffeecode.mock.chaos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import com.coffeecode.mock.config.MockProperties;

class ChaosEngineTest {

    private final ChaosEngine engine = new ChaosEngine(
            new MockProperties(Duration.ofMillis(100), Duration.ofMillis(800), 0, 0, Duration.ofSeconds(30), true));

    @Test
    void defaultScenarioIsSuccessfulWithinLatencyRange() {
        for (int i = 0; i < 1_000; i++) {
            Decision decision = engine.decide(Endpoint.CLIENTS, "C1234");
            assertThat(decision.fail()).isFalse();
            assertThat(decision.delay()).isBetween(Duration.ofMillis(100), Duration.ofMillis(800));
        }
    }

    @Test
    void magicIdsAreDeterministic() {
        assertThat(engine.decide(Endpoint.BALANCES, ChaosEngine.BALANCE_ERROR_ID).status()).isEqualTo(500);
        assertThat(engine.decide(Endpoint.CLIENTS, ChaosEngine.BALANCE_ERROR_ID).fail()).isFalse();
        assertThat(engine.decide(Endpoint.BALANCES, ChaosEngine.BALANCE_TIMEOUT_ID).delay()).isEqualTo(Duration.ofSeconds(30));
        assertThat(engine.decide(Endpoint.MOVEMENTS, ChaosEngine.MOVEMENTS_ERROR_ID).fail()).isTrue();
        assertThat(engine.decide(Endpoint.CLIENTS, ChaosEngine.HIGH_LATENCY_ID).delay()).isEqualTo(ChaosEngine.HIGH_LATENCY);
    }

    @Test
    void scenarioCanBeChangedAndReset() {
        engine.update(new Scenario(0, 0, 1.0, 0, 0));
        assertThat(engine.decide(Endpoint.CLIENTS, "C1234").status()).isEqualTo(503);

        engine.reset();
        assertThat(engine.decide(Endpoint.CLIENTS, "C1234").fail()).isFalse();
    }

    @Test
    void rejectsInvalidScenario() {
        assertThatThrownBy(() -> new Scenario(500, 100, 0, 0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Scenario(0, 0, 1.5, 0, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
