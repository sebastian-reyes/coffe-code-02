package com.coffeecode.banking.webflux.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.reactive.server.WebTestClient;

import com.coffeecode.contract.Contract;

/**
 * Core bancario apagado (nadie escucha en el puerto 1): la API responde 503 y el health lo refleja.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"banking.downstream.base-url=http://127.0.0.1:1", "banking.downstream.connect-timeout=500ms"})
@AutoConfigureWebTestClient
class CoreUnavailableApiTest {

    @Autowired
    WebTestClient http;

    @Test
    void connectionRefusedReturns503() {
        http.get().uri(Contract.summaryPath("C0001")).exchange()
                .expectStatus().isEqualTo(503)
                .expectBody().json(Contract.fixture("error-downstream-unavailable.json"), JsonCompareMode.STRICT);
    }

    @Test
    void healthReportsCoreBankingDown() {
        http.get().uri("/actuator/health").exchange()
                .expectStatus().isEqualTo(503)
                .expectBody().jsonPath("$.components.coreBanking.status").isEqualTo("DOWN");
    }
}
