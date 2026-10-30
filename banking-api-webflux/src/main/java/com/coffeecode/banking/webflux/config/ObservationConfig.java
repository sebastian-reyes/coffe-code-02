package com.coffeecode.banking.webflux.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.reactive.observation.ServerRequestObservationContext;
import org.springframework.web.reactive.function.client.ClientRequestObservationContext;

import io.micrometer.observation.ObservationPredicate;

/**
 * No observa (ni métricas ni trazas) las peticiones de /actuator ni el health check hacia el core:
 * Prometheus hace scrape cada 5 s y esas trazas taparían las de negocio en Tempo.
 */
@Configuration(proxyBeanMethods = false)
public class ObservationConfig {

    @Bean
    public ObservationPredicate ignoreActuatorObservations() {
        return (name, context) -> {
            if (context instanceof ServerRequestObservationContext server) {
                return !server.getCarrier().getPath().value().startsWith("/actuator");
            }
            if (context instanceof ClientRequestObservationContext client && client.getRequest() != null) {
                return !client.getRequest().url().getPath().startsWith("/actuator");
            }
            return true;
        };
    }
}
