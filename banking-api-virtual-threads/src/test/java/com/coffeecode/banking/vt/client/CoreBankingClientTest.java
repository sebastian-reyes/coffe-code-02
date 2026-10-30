package com.coffeecode.banking.vt.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import com.coffeecode.banking.vt.config.CoreBankingClientConfig;
import com.coffeecode.banking.vt.config.DownstreamProperties;
import com.coffeecode.banking.vt.error.DownstreamException;
import com.coffeecode.banking.vt.error.ErrorCode;
import com.coffeecode.contract.FakeCoreBanking;

/**
 * Verifica la configuración real de Apache HttpClient 5 (timeouts, pool) y el mapeo de errores.
 */
class CoreBankingClientTest {

    private static FakeCoreBanking fake;
    private final List<AutoCloseable> resources = new ArrayList<>();

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
    void closeClients() throws Exception {
        for (AutoCloseable resource : resources) {
            resource.close();
        }
    }

    private CoreBankingClient client(String baseUrl, int maxConnections, Duration responseTimeout) {
        var properties = new DownstreamProperties(baseUrl, Duration.ofMillis(500), responseTimeout,
                Duration.ofMillis(200), maxConnections, Duration.ofSeconds(30), 5);
        var config = new CoreBankingClientConfig();
        PoolingHttpClientConnectionManager pool = config.coreBankingConnectionManager(properties);
        CloseableHttpClient httpClient = config.coreBankingHttpClient(pool, properties);
        resources.add(httpClient);
        return new CoreBankingClient(config.coreBankingRestClient(RestClient.builder(), httpClient, properties));
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
        assertThat(client().getClient("C0001").fullName()).isEqualTo("Ana Torres");
        assertThat(client().getBalance("C0001").availableBalance()).isEqualByComparingTo("1520000.50");
    }

    @Test
    void movementsAreLimited() {
        assertThat(client().getMovements("C0001", 1))
                .singleElement()
                .satisfies(m -> assertThat(m.movementId()).isEqualTo("MV-0001-1"));
    }

    @Test
    void limitingMovementsDoesNotKillPooledConnections() {
        CoreBankingClient client = client();
        for (int i = 0; i < 20; i++) {
            assertThat(client.getMovements("C0001", 1)).hasSize(1);
        }
        assertThat(fake.distinctConnections()).as("20 llamadas secuenciales deben reutilizar 1 conexión").isEqualTo(1);
    }

    @Test
    void notFoundIsMappedToClientNotFound() {
        assertCode(catchThrowable(() -> client().getClient("C9999")), ErrorCode.CLIENT_NOT_FOUND);
    }

    @Test
    void serverErrorIsMappedToDownstreamError() {
        fake.fail(FakeCoreBanking.BALANCE_PATH + "C0001", 500);
        assertCode(catchThrowable(() -> client().getBalance("C0001")), ErrorCode.DOWNSTREAM_ERROR);
    }

    @Test
    void serviceUnavailableIsNotRetried() {
        // Apache reintenta 503 por defecto; con disableAutomaticRetries() debe llegar una sola petición.
        fake.fail(FakeCoreBanking.BALANCE_PATH + "C0001", 503);
        assertCode(catchThrowable(() -> client().getBalance("C0001")), ErrorCode.DOWNSTREAM_ERROR);
        assertThat(fake.hits(FakeCoreBanking.BALANCE_PATH + "C0001")).isEqualTo(1);
    }

    @Test
    void slowResponseIsMappedToTimeout() {
        fake.delay(FakeCoreBanking.CLIENT_PATH + "C0001", 1_500);
        assertCode(catchThrowable(() -> client(fake.baseUrl(), 10, Duration.ofMillis(300)).getClient("C0001")),
                ErrorCode.DOWNSTREAM_TIMEOUT);
    }

    @Test
    void connectionRefusedIsMappedToUnavailable() {
        assertCode(catchThrowable(() -> client("http://127.0.0.1:1", 10, Duration.ofMillis(500)).getClient("C0001")),
                ErrorCode.DOWNSTREAM_UNAVAILABLE);
    }

    @Test
    void exhaustedPoolIsMappedToPoolExhausted() throws Exception {
        fake.delay(FakeCoreBanking.CLIENT_PATH + "C0001", 600);
        CoreBankingClient singleConnection = client(fake.baseUrl(), 1, Duration.ofSeconds(2));

        // Dos llamadas simultáneas con una sola conexión: la segunda espera 200 ms y falla.
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> first = executor.submit(() -> singleConnection.getClient("C0001"));
            Future<?> second = executor.submit(() -> singleConnection.getClient("C0001"));
            List<Throwable> errors = new ArrayList<>();
            for (Future<?> f : List.of(first, second)) {
                try {
                    f.get();
                } catch (java.util.concurrent.ExecutionException e) {
                    errors.add(e.getCause());
                }
            }
            assertThat(errors).singleElement().satisfies(e -> assertCode(e, ErrorCode.CONNECTION_POOL_EXHAUSTED));
        }
    }
}
