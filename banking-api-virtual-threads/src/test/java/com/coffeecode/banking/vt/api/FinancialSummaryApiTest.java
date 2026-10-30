package com.coffeecode.banking.vt.api;

import static com.coffeecode.contract.FakeCoreBanking.BALANCE_PATH;
import static com.coffeecode.contract.FakeCoreBanking.CLIENT_PATH;
import static com.coffeecode.contract.FakeCoreBanking.MOVEMENTS_PATH;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.client.RestClient;

import com.coffeecode.contract.ConcurrentRequests;
import com.coffeecode.contract.Contract;
import com.coffeecode.contract.FakeCoreBanking;

/**
 * Pruebas end-to-end contra un core falso. Los cuerpos esperados son los ficheros de
 * contract-test-support: los mismos que usa banking-api-webflux.
 *
 * <p>MockMvc para el contrato; HTTP real (Tomcat + hilos virtuales) para concurrencia y modelo de hilos.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "banking.downstream.response-timeout=500ms",
                "banking.downstream.pending-acquire-timeout=300ms",
                "banking.demo.blocking-endpoints-enabled=true"
        })
@AutoConfigureMockMvc
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
    MockMvc mvc;

    @LocalServerPort
    int port;

    @BeforeEach
    void resetFake() {
        fake.reset();
    }

    private ResultActions getSummary(String clientId) throws Exception {
        return mvc.perform(get(Contract.summaryPath(clientId)));
    }

    private RestClient realHttp() {
        return RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void successMatchesContract() throws Exception {
        getSummary("C0001")
                .andExpect(status().isOk())
                .andExpect(header().string("X-Implementation", "virtual-threads"))
                .andExpect(content().json(Contract.fixture("financial-summary-C0001.json"), JsonCompareMode.STRICT));
    }

    @Test
    void invalidClientIdReturns400() throws Exception {
        getSummary("12345")
                .andExpect(status().isBadRequest())
                .andExpect(content().json(Contract.fixture("error-invalid-client-id.json"), JsonCompareMode.STRICT));
        assertThat(fake.hits(CLIENT_PATH + "12345")).as("no debe llamar al core").isZero();
    }

    @Test
    void unknownClientReturns404() throws Exception {
        fake.stub(CLIENT_PATH + "C0001", 404, "{}", 0).stub(BALANCE_PATH + "C0001", 404, "{}", 0);
        getSummary("C0001")
                .andExpect(status().isNotFound())
                .andExpect(content().json(Contract.fixture("error-client-not-found.json"), JsonCompareMode.STRICT));
    }

    @Test
    void downstreamErrorReturns502() throws Exception {
        fake.fail(BALANCE_PATH + "C0001", 500);
        getSummary("C0001")
                .andExpect(status().is(502))
                .andExpect(content().json(Contract.fixture("error-downstream-error.json"), JsonCompareMode.STRICT));
    }

    @Test
    void downstreamTimeoutReturns504() throws Exception {
        fake.delay(BALANCE_PATH + "C0001", 1_500);
        long start = System.nanoTime();
        getSummary("C0001")
                .andExpect(status().is(504))
                .andExpect(content().json(Contract.fixture("error-downstream-timeout.json"), JsonCompareMode.STRICT));
        assertThat(elapsedMs(start)).as("corta en el response-timeout (500 ms), no espera al core").isLessThan(1_400);
    }

    @Test
    void mandatoryFailureFailsFast() throws Exception {
        fake.delay(CLIENT_PATH + "C0001", 1_000).fail(BALANCE_PATH + "C0001", 500);
        long start = System.nanoTime();
        getSummary("C0001").andExpect(status().is(502));
        assertThat(elapsedMs(start)).as("no espera a la llamada de cliente (1 s)").isLessThan(900);
    }

    @Test
    void movementsFailureDegradesTo200WithWarning() throws Exception {
        fake.fail(MOVEMENTS_PATH + "C0001", 500);
        getSummary("C0001")
                .andExpect(status().isOk())
                .andExpect(content().json(Contract.fixture("financial-summary-C0001-degraded.json"), JsonCompareMode.STRICT));
    }

    @Test
    void highLatencyBelowTimeoutStillSucceeds() throws Exception {
        fake.delay(CLIENT_PATH + "C0001", 400).delay(BALANCE_PATH + "C0001", 400).delay(MOVEMENTS_PATH + "C0001", 400);
        getSummary("C0001").andExpect(status().isOk());
    }

    @Test
    void independentCallsAreConcurrent() throws Exception {
        fake.delay(CLIENT_PATH + "C0001", 300).delay(BALANCE_PATH + "C0001", 300).delay(MOVEMENTS_PATH + "C0001", 300);
        long start = System.nanoTime();
        getSummary("C0001").andExpect(status().isOk());
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
    void tomcatServesRequestsOnVirtualThreads() {
        String body = realHttp().get().uri("/api/demo/legacy-score/C0001?mode=direct").retrieve().body(String.class);
        assertThat(body).contains("\"virtual\":true");

        String pinned = realHttp().get().uri("/api/demo/legacy-score/C0001?mode=synchronized").retrieve().body(String.class);
        assertThat(pinned).contains("\"virtual\":true");
    }

    @Test
    void exposesHealthAndMetrics() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.coreBanking.status").value("UP"));

        getSummary("C0001").andExpect(status().isOk());
        // http.server.requests solo se registra con peticiones reales (no con MockMvc): hacemos una por HTTP.
        realHttp().get().uri(Contract.summaryPath("C0001")).retrieve().toBodilessEntity();
        String prometheus = realHttp().get().uri("/actuator/prometheus").retrieve().body(String.class);
        assertThat(prometheus)
                .contains("http_server_requests_seconds")
                .contains("http_client_requests_seconds")
                .contains("httpcomponents_httpclient_pool");
    }

    private static long elapsedMs(long startNanos) {
        return Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
    }
}
