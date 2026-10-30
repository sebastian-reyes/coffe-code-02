package com.coffeecode.banking.vt.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.observation.ClientRequestObservationContext;
import org.springframework.http.server.observation.ServerRequestObservationContext;

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
                return !server.getCarrier().getRequestURI().startsWith("/actuator");
            }
            if (context instanceof ClientRequestObservationContext client && client.getCarrier() != null) {
                return !client.getCarrier().getURI().getPath().startsWith("/actuator");
            }
            return true;
        };
    }
}
