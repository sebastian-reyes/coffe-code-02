package com.coffeecode.banking.webflux.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import com.coffeecode.contract.Contract;
import com.coffeecode.contract.FakeCoreBanking;

/**
 * Con tracing activo, la traza de la petición entrante debe llegar a las TRES llamadas al core.
 * En WebFlux viaja en el Reactor Context, sin código adicional. Mismo test en banking-api-virtual-threads.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"management.tracing.export.enabled=true", "management.tracing.sampling.probability=1.0"})
@AutoConfigureWebTestClient
class TracePropagationApiTest {

    static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    static final FakeCoreBanking fake = FakeCoreBanking.start();

    @DynamicPropertySource
    static void downstream(DynamicPropertyRegistry registry) {
        registry.add("banking.downstream.base-url", fake::baseUrl);
    }

    @AfterAll
    static void stopFake() {
        fake.close();
    }

    @Autowired
    WebTestClient http;

    @Test
    void incomingTraceReachesEveryDownstreamCall() {
        fake.reset();
        http.get().uri(Contract.summaryPath("C0001"))
                .header("traceparent", "00-" + TRACE_ID + "-00f067aa0ba902b7-01")
                .exchange()
                .expectStatus().isOk();

        assertThat(fake.traceparents())
                .as("clients, balances y movements deben pertenecer a la traza entrante")
                .hasSize(3)
                .allSatisfy(header -> assertThat(header).startsWith("00-" + TRACE_ID + "-"));
    }
}
