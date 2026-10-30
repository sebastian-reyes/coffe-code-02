package com.coffeecode.banking.vt.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

import com.coffeecode.contract.Contract;
import com.coffeecode.contract.FakeCoreBanking;

/**
 * Con tracing activo, la traza de la petición entrante debe llegar a las TRES llamadas al core, aunque
 * se ejecuten en otros hilos virtuales (FailFastTasks propaga el contexto). Mismo test en banking-api-webflux.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"management.tracing.export.enabled=true", "management.tracing.sampling.probability=1.0"})
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

    @LocalServerPort
    int port;

    @Test
    void incomingTraceReachesEveryDownstreamCall() {
        fake.reset();
        RestClient.create("http://localhost:" + port).get()
                .uri(Contract.summaryPath("C0001"))
                .header("traceparent", "00-" + TRACE_ID + "-00f067aa0ba902b7-01")
                .retrieve()
                .toBodilessEntity();

        assertThat(fake.traceparents())
                .as("clients, balances y movements deben pertenecer a la traza entrante")
                .hasSize(3)
                .allSatisfy(header -> assertThat(header).startsWith("00-" + TRACE_ID + "-"));
    }
}
