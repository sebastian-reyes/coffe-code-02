package com.coffeecode.banking.vt.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.coffeecode.banking.vt.client.CoreBankingClient;
import com.coffeecode.banking.vt.client.CoreDtos.CoreBalance;
import com.coffeecode.banking.vt.client.CoreDtos.CoreClient;
import com.coffeecode.banking.vt.client.CoreDtos.CoreMovement;
import com.coffeecode.banking.vt.config.DownstreamProperties;
import com.coffeecode.banking.vt.error.DownstreamException;
import com.coffeecode.banking.vt.error.ErrorCode;

/**
 * Mismos escenarios que FinancialSummaryServiceTest de WebFlux, con tiempo real (no hay reloj virtual).
 */
class FinancialSummaryServiceTest {

    private static final CoreClient CLIENT = new CoreClient("C0001", "Ana Torres", "PREMIUM");
    private static final CoreBalance BALANCE = new CoreBalance("C0001", "0012-0001-123456", "COP",
            new BigDecimal("1520000.50"), new BigDecimal("1600000.50"));
    private static final CoreMovement MOVEMENT = new CoreMovement("MV-1", LocalDate.of(2026, 10, 1), "Pago",
            new BigDecimal("10.00"), "CREDIT");

    private final CoreBankingClient core = mock(CoreBankingClient.class);
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final FinancialSummaryService service = new FinancialSummaryService(core, executor,
            new DownstreamProperties("http://unused", Duration.ofSeconds(1), Duration.ofSeconds(2),
                    Duration.ofSeconds(1), 10, Duration.ofSeconds(30), 5));

    @AfterEach
    void closeExecutor() {
        executor.close();
    }

    private static <T> T after(long millis, T value) throws InterruptedException {
        Thread.sleep(millis);
        return value;
    }

    @Test
    void independentCallsRunConcurrently() {
        when(core.getClient("C0001")).thenAnswer(i -> after(300, CLIENT));
        when(core.getBalance("C0001")).thenAnswer(i -> after(300, BALANCE));
        when(core.getMovements("C0001", 5)).thenAnswer(i -> after(300, List.of(MOVEMENT)));

        long start = System.nanoTime();
        var summary = service.getSummary("C0001");
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        // Si fueran secuenciales tardarían 900 ms; concurrentes, ~300 ms.
        assertThat(elapsed).isBetween(290L, 700L);
        assertThat(summary.account().heldAmount()).isEqualByComparingTo("80000.00");
        assertThat(summary.recentMovements()).hasSize(1);
        assertThat(summary.warnings()).isEmpty();
    }

    @Test
    void mandatoryFailureFailsFastAndCancelsPendingCalls() throws InterruptedException {
        CountDownLatch clientInterrupted = new CountDownLatch(1);
        when(core.getClient("C0001")).thenAnswer(i -> {
            try {
                return after(1_000, CLIENT);
            } catch (InterruptedException e) {
                clientInterrupted.countDown();   // cancel(true) interrumpe el hilo virtual
                throw e;
            }
        });
        when(core.getBalance("C0001")).thenAnswer(i -> {
            Thread.sleep(50);
            throw new DownstreamException(ErrorCode.DOWNSTREAM_ERROR, "balances", null);
        });
        when(core.getMovements("C0001", 5)).thenAnswer(i -> after(100, List.of(MOVEMENT)));

        long start = System.nanoTime();
        Throwable error = catchThrowable(() -> service.getSummary("C0001"));
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertThat(error).isInstanceOf(DownstreamException.class);
        assertThat(((DownstreamException) error).code()).isEqualTo(ErrorCode.DOWNSTREAM_ERROR);
        assertThat(elapsed).as("no espera a la llamada de cliente (1 s)").isLessThan(600);
        assertThat(clientInterrupted.await(1, TimeUnit.SECONDS)).as("la llamada pendiente debe cancelarse").isTrue();
    }

    @Test
    void optionalMovementsFailureDegradesResponse() {
        when(core.getClient("C0001")).thenReturn(CLIENT);
        when(core.getBalance("C0001")).thenReturn(BALANCE);
        when(core.getMovements("C0001", 5)).thenThrow(
                new DownstreamException(ErrorCode.DOWNSTREAM_TIMEOUT, "movements", null));

        var summary = service.getSummary("C0001");

        assertThat(summary.recentMovements()).isEmpty();
        assertThat(summary.warnings()).containsExactly("MOVEMENTS_UNAVAILABLE");
    }

    @Test
    void callsRunOnVirtualThreads() {
        when(core.getClient("C0001")).thenAnswer(i -> {
            assertThat(Thread.currentThread().isVirtual()).isTrue();
            return CLIENT;
        });
        when(core.getBalance("C0001")).thenReturn(BALANCE);
        when(core.getMovements("C0001", 5)).thenReturn(List.of());

        assertThat(service.getSummary("C0001").clientId()).isEqualTo("C0001");
    }
}
