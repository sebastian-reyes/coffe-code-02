package com.coffeecode.banking.vt.health;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Comprueba que el core bancario responde. Aparece como {@code coreBanking} en /actuator/health.
 *
 * <p>Nota: en producción no conviene que la liveness dependa de un sistema externo (reinicios en cascada);
 * aquí sirve para diagnosticar rápidamente en la demo si el mock está levantado.
 */
@Component("coreBanking")
public class CoreBankingHealthIndicator implements HealthIndicator {

    private final RestClient restClient;

    public CoreBankingHealthIndicator(RestClient coreBankingRestClient) {
        this.restClient = coreBankingRestClient;
    }

    @Override
    public Health health() {
        try {
            var response = restClient.get().uri("/actuator/health").retrieve().toBodilessEntity();
            return Health.up().withDetail("status", response.getStatusCode().value()).build();
        } catch (RuntimeException e) {
            return Health.down().withDetail("error", e.getClass().getSimpleName()).build();
        }
    }
}
