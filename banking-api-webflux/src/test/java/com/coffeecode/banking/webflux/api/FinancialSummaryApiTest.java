package com.coffeecode.banking.webflux.api;

import static com.coffeecode.contract.FakeCoreBanking.BALANCE_PATH;
import static com.coffeecode.contract.FakeCoreBanking.CLIENT_PATH;
import static com.coffeecode.contract.FakeCoreBanking.MOVEMENTS_PATH;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.reactive.server.WebTestClient;

import com.coffeecode.contract.ConcurrentRequests;
import com.coffeecode.contract.Contract;
import com.coffeecode.contract.FakeCoreBanking;

/**
 * Pruebas end-to-end por HTTP contra un core falso. Los cuerpos esperados son los ficheros de
 * contract-test-support: los mismos que usa banking-api-virtual-threads.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "banking.downstream.response-timeout=500ms",
                "banking.downstream.pending-acquire-timeout=300ms",
                "banking.demo.blocking-endpoints-enabled=true"
        })
@AutoConfigureWebTestClient
class FinancialSummaryApiTest {

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

    @LocalServerPort
    int port;

    @BeforeEach
    void resetFake() {
        fake.reset();
    }

    private WebTestClient.ResponseSpec getSummary(String clientId) {
        return http.mutate().responseTimeout(Duration.ofSeconds(5)).build()
                .get().uri(Contract.summaryPath(clientId)).exchange();
    }

    @Test
    void successMatchesContract() {
        getSummary("C0001")
                .expectStatus().isOk()
                .expectHeader().valueEquals("X-Implementation", "webflux")
                .expectBody().json(Contract.fixture("financial-summary-C0001.json"), JsonCompareMode.STRICT);
    }

    @Test
    void invalidClientIdReturns400() {
        getSummary("12345")
                .expectStatus().isBadRequest()
                .expectBody().json(Contract.fixture("error-invalid-client-id.json"), JsonCompareMode.STRICT);
        assertThat(fake.hits(CLIENT_PATH + "12345")).as("no debe llamar al core").isZero();
    }

    @Test
    void unknownClientReturns404() {
        fake.stub(CLIENT_PATH + "C0001", 404, "{}", 0).stub(BALANCE_PATH + "C0001", 404, "{}", 0);
        getSummary("C0001")
                .expectStatus().isNotFound()
                .expectBody().json(Contract.fixture("error-client-not-found.json"), JsonCompareMode.STRICT);
    }

    @Test
    void downstreamErrorReturns502() {
        fake.fail(BALANCE_PATH + "C0001", 500);
        getSummary("C0001")
                .expectStatus().isEqualTo(502)
                .expectBody().json(Contract.fixture("error-downstream-error.json"), JsonCompareMode.STRICT);
    }

    @Test
    void downstreamTimeoutReturns504() {
        fake.delay(BALANCE_PATH + "C0001", 1_500);
        long start = System.nanoTime();
        getSummary("C0001")
                .expectStatus().isEqualTo(504)
                .expectBody().json(Contract.fixture("error-downstream-timeout.json"), JsonCompareMode.STRICT);
        assertThat(elapsedMs(start)).as("corta en el response-timeout (500 ms), no espera al core").isLessThan(1_400);
    }

    @Test
    void mandatoryFailureFailsFast() {
        fake.delay(CLIENT_PATH + "C0001", 1_000).fail(BALANCE_PATH + "C0001", 500);
        long start = System.nanoTime();
        getSummary("C0001").expectStatus().isEqualTo(502);
        assertThat(elapsedMs(start)).as("no espera a la llamada de cliente (1 s)").isLessThan(900);
    }

    @Test
    void movementsFailureDegradesTo200WithWarning() {
        fake.fail(MOVEMENTS_PATH + "C0001", 500);
        getSummary("C0001")
                .expectStatus().isOk()
                .expectBody().json(Contract.fixture("financial-summary-C0001-degraded.json"), JsonCompareMode.STRICT);
    }

    @Test
    void highLatencyBelowTimeoutStillSucceeds() {
        fake.delay(CLIENT_PATH + "C0001", 400).delay(BALANCE_PATH + "C0001", 400).delay(MOVEMENTS_PATH + "C0001", 400);
        getSummary("C0001").expectStatus().isOk();
    }

    @Test
    void independentCallsAreConcurrent() {
        fake.delay(CLIENT_PATH + "C0001", 300).delay(BALANCE_PATH + "C0001", 300).delay(MOVEMENTS_PATH + "C0001", 300);
        long start = System.nanoTime();
        getSummary("C0001").expectStatus().isOk();
        assertThat(elapsedMs(start)).as("secuencial serían >= 900 ms").isLessThan(800);
        assertThat(fake.maxInFlight()).isGreaterThanOrEqualTo(3);
    }

    @Test
    void handlesManySimultaneousRequests() {
        fake.delay(CLIENT_PATH + "C0001", 200).delay(BALANCE_PATH + "C0001", 200).delay(MOVEMENTS_PATH + "C0001", 200);
        long start = System.nanoTime();
        // 50 peticiones = 150 conexiones nuevas al core falso a la vez. Con 100 (300 conexiones) el HttpServer
        // del JDK no aceptaba a tiempo en Windows con la máquina cargada y rechazaba conexiones (troubleshooting, caso 1).
        List<Integer> statuses = ConcurrentRequests.fire("http://localhost:" + port + Contract.summaryPath("C0001"), 50);
        assertThat(statuses).hasSize(50).containsOnly(200);
        assertThat(fake.hits(CLIENT_PATH + "C0001")).isEqualTo(50);
        assertThat(elapsedMs(start)).as("50 peticiones en paralelo, no en serie (serían 10 s)").isLessThan(5_000);
    }

    @Test
    void blockInsideEventLoopIsDetectedByReactor() {
        http.get().uri("/api/demo/legacy-score/C0001?mode=block").exchange()
                .expectStatus().isEqualTo(500)
                .expectBody().jsonPath("$.code").isEqualTo("INTERNAL_ERROR");
    }

    @Test
    void blockingCallRunsOnEventLoopUnlessOffloaded() {
        http.get().uri("/api/demo/legacy-score/C0001?mode=event-loop").exchange()
                .expectStatus().isOk()
                // Hilo del event loop: "reactor-http-nio-N" o "webflux-http-nio-N" según los LoopResources activos.
                .expectBody().jsonPath("$.thread").value(String.class, t -> assertThat(t).contains("-http-nio-"));

        http.get().uri("/api/demo/legacy-score/C0001?mode=offload").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.thread").value(String.class, t -> assertThat(t).contains("boundedElastic"));
    }

    @Test
    void exposesHealthAndMetrics() {
        http.get().uri("/actuator/health").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.components.coreBanking.status").isEqualTo("UP");

        getSummary("C0001").expectStatus().isOk();
        // Con histogramas de percentiles el scrape supera los 256 KB por defecto de los codecs.
        http.mutate().codecs(c -> c.defaultCodecs().maxInMemorySize(10 * 1024 * 1024)).build()
                .get().uri("/actuator/prometheus").exchange()
                .expectStatus().isOk()
                .expectBody(String.class).value(body -> assertThat(body)
                        .contains("http_server_requests_seconds")
                        .contains("http_client_requests_seconds")
                        .contains("reactor_netty_connection_provider"));
    }

    private static long elapsedMs(long startNanos) {
        return Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
    }
}
