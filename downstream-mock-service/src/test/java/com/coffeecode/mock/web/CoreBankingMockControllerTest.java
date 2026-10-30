package com.coffeecode.mock.web;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import com.coffeecode.mock.chaos.ChaosEngine;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"mock.latency-min=0ms", "mock.latency-max=5ms", "mock.hang-duration=200ms"})
@AutoConfigureWebTestClient
class CoreBankingMockControllerTest {

    @Autowired
    WebTestClient client;

    @Autowired
    ChaosEngine chaos;

    @AfterEach
    void resetScenario() {
        chaos.reset();
    }

    @Test
    void returnsClientBalanceAndMovements() {
        client.get().uri("/mock/clients/C0001").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.clientId").isEqualTo("C0001")
                .jsonPath("$.fullName").isNotEmpty()
                .jsonPath("$.segment").isNotEmpty();

        client.get().uri("/mock/balances/C0001").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.currency").isNotEmpty()
                .jsonPath("$.availableBalance").isNumber();

        client.get().uri("/mock/movements/C0001?limit=3").accept(MediaType.APPLICATION_JSON).exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.length()").isEqualTo(3)
                .jsonPath("$[0].movementId").isEqualTo("MV-0001-1");
    }

    @Test
    void sameClientAlwaysReturnsSameData() {
        byte[] first = client.get().uri("/mock/balances/C4321").exchange().expectBody().returnResult().getResponseBody();
        client.get().uri("/mock/balances/C4321").exchange().expectBody().json(new String(first));
    }

    @Test
    void unknownClientReturns404() {
        client.get().uri("/mock/clients/" + ChaosEngine.NOT_FOUND_ID).exchange().expectStatus().isNotFound();
        client.get().uri("/mock/balances/XYZ").exchange().expectStatus().isNotFound();
    }

    @Test
    void magicIdReturns500OnBalanceOnly() {
        client.get().uri("/mock/clients/" + ChaosEngine.BALANCE_ERROR_ID).exchange().expectStatus().isOk();
        client.get().uri("/mock/balances/" + ChaosEngine.BALANCE_ERROR_ID).exchange()
                .expectStatus().isEqualTo(500)
                .expectBody().jsonPath("$.code").isEqualTo("CORE_UNAVAILABLE");
    }

    @Test
    void adminEndpointChangesScenario() {
        client.put().uri("/mock/admin/scenario")
                .bodyValue("{\"latencyMinMs\":0,\"latencyMaxMs\":0,\"errorRate\":1.0,\"timeoutRate\":0.0,\"hangMs\":0}")
                .header("Content-Type", "application/json")
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.errorRate").isEqualTo(1.0);

        client.get().uri("/mock/clients/C0001").exchange().expectStatus().isEqualTo(503);

        client.post().uri("/mock/admin/scenario/reset").exchange().expectStatus().isOk();
        client.get().uri("/mock/clients/C0001").exchange().expectStatus().isOk();
    }

    @Test
    void adminRejectsInvalidScenario() {
        client.put().uri("/mock/admin/scenario")
                .header("Content-Type", "application/json")
                .bodyValue("{\"latencyMinMs\":900,\"latencyMaxMs\":100,\"errorRate\":0,\"timeoutRate\":0,\"hangMs\":0}")
                .exchange()
                .expectStatus().isBadRequest();
    }
}
