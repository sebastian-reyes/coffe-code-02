package com.coffeecode.banking.webflux.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import com.coffeecode.banking.webflux.config.CoreBankingClientConfig;
import com.coffeecode.banking.webflux.config.DownstreamProperties;
import com.coffeecode.banking.webflux.error.DownstreamException;
import com.coffeecode.banking.webflux.error.ErrorCode;
import com.coffeecode.contract.FakeCoreBanking;

import reactor.core.publisher.Mono;
import reactor.netty.resources.ConnectionProvider;
import reactor.test.StepVerifier;

/**
 * Verifica la configuración real de Reactor Netty (timeouts, pool) y el mapeo de errores.
 */
class CoreBankingClientTest {

    private static FakeCoreBanking fake;
    private final List<ConnectionProvider> providers = new ArrayList<>();

    @BeforeAll
    static void startFake() {
        fake = FakeCoreBanking.start();
    }

    @AfterAll
    static void stopFake() {
        fake.close();
    }

    @BeforeEach
    void resetFake() {
        fake.reset();
    }

    @AfterEach
    void disposePools() {
        providers.forEach(ConnectionProvider::dispose);
    }

    private CoreBankingClient client(String baseUrl, int maxConnections, Duration responseTimeout) {
        var properties = new DownstreamProperties(baseUrl, Duration.ofMillis(500), responseTimeout,
                Duration.ofMillis(200), maxConnections, Duration.ofSeconds(30), 5);
        var config = new CoreBankingClientConfig();
        ConnectionProvider provider = config.coreBankingConnectionProvider(properties);
        providers.add(provider);
        return new CoreBankingClient(config.coreBankingWebClient(WebClient.builder(), provider, properties));
    }

    private CoreBankingClient client() {
        return client(fake.baseUrl(), 10, Duration.ofMillis(500));
    }

    private static void assertCode(Throwable error, ErrorCode expected) {
        assertThat(error).isInstanceOf(DownstreamException.class);
        assertThat(((DownstreamException) error).code()).isEqualTo(expected);
    }

    @Test
    void decodesClientAndBalance() {
        StepVerifier.create(client().getClient("C0001"))
                .assertNext(c -> assertThat(c.fullName()).isEqualTo("Ana Torres"))
                .verifyComplete();

        StepVerifier.create(client().getBalance("C0001"))
                .assertNext(b -> assertThat(b.availableBalance()).isEqualByComparingTo("1520000.50"))
                .verifyComplete();
    }

    @Test
    void movementsAreStreamedAsFluxAndLimited() {
        StepVerifier.create(client().getMovements("C0001", 1))
                .assertNext(m -> assertThat(m.movementId()).isEqualTo("MV-0001-1"))
                .verifyComplete();
    }

    @Test
    void limitingMovementsDoesNotKillPooledConnections() {
        // Regresión: con take(limit) cada llamada cancelaba el Flux y Reactor Netty cerraba la conexión.
        CoreBankingClient client = client();
        for (int i = 0; i < 20; i++) {
            StepVerifier.create(client.getMovements("C0001", 1)).expectNextCount(1).verifyComplete();
        }
        assertThat(fake.distinctConnections()).as("20 llamadas secuenciales deben reutilizar 1 conexión").isEqualTo(1);
    }

    @Test
    void notFoundIsMappedToClientNotFound() {
        StepVerifier.create(client().getClient("C9999"))
                .expectErrorSatisfies(e -> assertCode(e, ErrorCode.CLIENT_NOT_FOUND))
                .verify();
    }

    @Test
    void serverErrorIsMappedToDownstreamError() {
        fake.fail(FakeCoreBanking.BALANCE_PATH + "C0001", 500);
        StepVerifier.create(client().getBalance("C0001"))
                .expectErrorSatisfies(e -> assertCode(e, ErrorCode.DOWNSTREAM_ERROR))
                .verify();
    }

    @Test
    void slowResponseIsMappedToTimeout() {
        fake.delay(FakeCoreBanking.CLIENT_PATH + "C0001", 1_500);
        StepVerifier.create(client(fake.baseUrl(), 10, Duration.ofMillis(300)).getClient("C0001"))
                .expectErrorSatisfies(e -> assertCode(e, ErrorCode.DOWNSTREAM_TIMEOUT))
                .verify(Duration.ofSeconds(2));
    }

    @Test
    void connectionRefusedIsMappedToUnavailable() {
        StepVerifier.create(client("http://127.0.0.1:1", 10, Duration.ofMillis(500)).getClient("C0001"))
                .expectErrorSatisfies(e -> assertCode(e, ErrorCode.DOWNSTREAM_UNAVAILABLE))
                .verify(Duration.ofSeconds(5));
    }

    @Test
    void exhaustedPoolIsMappedToPoolExhausted() {
        fake.delay(FakeCoreBanking.CLIENT_PATH + "C0001", 600);
        CoreBankingClient singleConnection = client(fake.baseUrl(), 1, Duration.ofSeconds(2));

        // Dos llamadas simultáneas con una sola conexión: la segunda espera 200 ms y falla.
        StepVerifier.create(Mono.zip(singleConnection.getClient("C0001"), singleConnection.getClient("C0001")))
                .expectErrorSatisfies(e -> assertCode(e, ErrorCode.CONNECTION_POOL_EXHAUSTED))
                .verify(Duration.ofSeconds(2));
    }
}
