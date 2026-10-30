package com.coffeecode.banking.webflux.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Conexión con el core bancario. Las mismas propiedades (nombres y valores por defecto) existen
 * en banking-api-virtual-threads para que la comparación sea justa.
 *
 * @param baseUrl               URL del core (downstream-mock-service)
 * @param connectTimeout        tiempo máximo para establecer la conexión TCP
 * @param responseTimeout       tiempo máximo de espera de la respuesta, por llamada
 * @param pendingAcquireTimeout tiempo máximo esperando una conexión libre del pool
 * @param maxConnections        tamaño máximo del pool de conexiones hacia el core
 * @param maxIdleTime           tiempo que una conexión ociosa permanece en el pool
 * @param movementsLimit        número de movimientos recientes a incluir en el resumen
 */
@ConfigurationProperties("banking.downstream")
public record DownstreamProperties(
        @DefaultValue("http://localhost:8090") String baseUrl,
        @DefaultValue("1s") Duration connectTimeout,
        @DefaultValue("2s") Duration responseTimeout,
        @DefaultValue("1s") Duration pendingAcquireTimeout,
        @DefaultValue("500") int maxConnections,
        @DefaultValue("30s") Duration maxIdleTime,
        @DefaultValue("5") int movementsLimit) {
}
