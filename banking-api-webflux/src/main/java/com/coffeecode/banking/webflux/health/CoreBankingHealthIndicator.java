package com.coffeecode.banking.webflux.health;

import java.time.Duration;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.ReactiveHealthIndicator;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import reactor.core.publisher.Mono;

/**
 * Comprueba que el core bancario responde. Aparece como {@code coreBanking} en /actuator/health.
 *
 * <p>Nota: en producción no conviene que la liveness dependa de un sistema externo (reinicios en cascada);
 * aquí sirve para diagnosticar rápidamente en la demo si el mock está levantado.
 */
@Component("coreBanking")
public class CoreBankingHealthIndicator implements ReactiveHealthIndicator {

    private final WebClient webClient;

    public CoreBankingHealthIndicator(WebClient coreBankingWebClient) {
        this.webClient = coreBankingWebClient;
    }

    @Override
    public Mono<Health> health() {
        return webClient.get().uri("/actuator/health")
                .retrieve()
                .toBodilessEntity()
                .timeout(Duration.ofSeconds(1))
                .map(response -> Health.up().withDetail("status", response.getStatusCode().value()).build())
                .onErrorResume(e -> Mono.just(Health.down().withDetail("error", e.getClass().getSimpleName()).build()));
    }
}
