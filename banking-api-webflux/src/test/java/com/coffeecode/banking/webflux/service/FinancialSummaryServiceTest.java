package com.coffeecode.banking.webflux.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import com.coffeecode.banking.webflux.client.CoreBankingClient;
import com.coffeecode.banking.webflux.client.CoreDtos.CoreBalance;
import com.coffeecode.banking.webflux.client.CoreDtos.CoreClient;
import com.coffeecode.banking.webflux.client.CoreDtos.CoreMovement;
import com.coffeecode.banking.webflux.config.DownstreamProperties;
import com.coffeecode.banking.webflux.error.DownstreamException;
import com.coffeecode.banking.webflux.error.ErrorCode;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Usa tiempo virtual de Reactor: los 300 ms "pasan" sin esperar de verdad.
 */
class FinancialSummaryServiceTest {

    private static final CoreClient CLIENT = new CoreClient("C0001", "Ana Torres", "PREMIUM");
    private static final CoreBalance BALANCE = new CoreBalance("C0001", "0012-0001-123456", "COP",
            new BigDecimal("1520000.50"), new BigDecimal("1600000.50"));
    private static final CoreMovement MOVEMENT = new CoreMovement("MV-1", LocalDate.of(2026, 10, 1), "Pago",
            new BigDecimal("10.00"), "CREDIT");

    private final CoreBankingClient core = mock(CoreBankingClient.class);
    private final FinancialSummaryService service = new FinancialSummaryService(core,
            new DownstreamProperties("http://unused", Duration.ofSeconds(1), Duration.ofSeconds(2),
                    Duration.ofSeconds(1), 10, Duration.ofSeconds(30), 5));

    @Test
    void independentCallsRunConcurrently() {
        // thenAnswer: el Mono.delay se crea dentro de withVirtualTime, así usa el reloj virtual.
        when(core.getClient("C0001")).thenAnswer(i -> Mono.delay(Duration.ofMillis(300)).thenReturn(CLIENT));
        when(core.getBalance("C0001")).thenAnswer(i -> Mono.delay(Duration.ofMillis(300)).thenReturn(BALANCE));
        when(core.getMovements("C0001", 5)).thenAnswer(i -> Flux.just(MOVEMENT).delaySubscription(Duration.ofMillis(300)));

        // Si fueran secuenciales tardarían 900 ms; concurrentes, 300 ms.
        StepVerifier.withVirtualTime(() -> service.getSummary("C0001"))
                .expectSubscription()
                .expectNoEvent(Duration.ofMillis(299))
                .thenAwait(Duration.ofMillis(1))
                .assertNext(summary -> {
                    assertThat(summary.account().heldAmount()).isEqualByComparingTo("80000.00");
                    assertThat(summary.recentMovements()).hasSize(1);
                    assertThat(summary.warnings()).isEmpty();
                })
                .verifyComplete();
    }

    @Test
    void mandatoryFailureFailsFastAndCancelsPendingCalls() {
        AtomicBoolean clientCancelled = new AtomicBoolean();
        when(core.getClient("C0001")).thenAnswer(i -> Mono.delay(Duration.ofSeconds(1)).thenReturn(CLIENT)
                .doOnCancel(() -> clientCancelled.set(true)));
        when(core.getBalance("C0001")).thenAnswer(i -> Mono.delay(Duration.ofMillis(50))
                .then(Mono.<CoreBalance>error(new DownstreamException(ErrorCode.DOWNSTREAM_ERROR, "balances", null))));
        when(core.getMovements("C0001", 5)).thenAnswer(i -> Flux.just(MOVEMENT).delaySubscription(Duration.ofMillis(100)));

        StepVerifier.withVirtualTime(() -> service.getSummary("C0001"))
                .expectSubscription()
                .thenAwait(Duration.ofMillis(50))
                .expectErrorSatisfies(e -> assertThat(((DownstreamException) e).code()).isEqualTo(ErrorCode.DOWNSTREAM_ERROR))
                .verify();

        assertThat(clientCancelled).as("la llamada de cliente pendiente debe cancelarse").isTrue();
    }

    @Test
    void optionalMovementsFailureDegradesResponse() {
        when(core.getClient("C0001")).thenReturn(Mono.just(CLIENT));
        when(core.getBalance("C0001")).thenReturn(Mono.just(BALANCE));
        when(core.getMovements("C0001", 5)).thenReturn(
                Flux.error(new DownstreamException(ErrorCode.DOWNSTREAM_TIMEOUT, "movements", null)));

        StepVerifier.create(service.getSummary("C0001"))
                .assertNext(summary -> {
                    assertThat(summary.recentMovements()).isEmpty();
                    assertThat(summary.warnings()).containsExactly("MOVEMENTS_UNAVAILABLE");
                })
                .verifyComplete();
    }
}
