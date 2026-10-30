package com.coffeecode.banking.vt.config;

import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.binder.httpcomponents.hc5.PoolingHttpClientConnectionManagerMetricsBinder;

/**
 * RestClient hacia el core bancario sobre Apache HttpClient 5, con pool y timeouts explícitos.
 *
 * <p>Equivalencias con banking-api-webflux (Reactor Netty):
 * <pre>
 *   maxTotal / maxPerRoute         -> ConnectionProvider.maxConnections
 *   connectionRequestTimeout       -> ConnectionProvider.pendingAcquireTimeout
 *   ConnectionConfig.connectTimeout-> ChannelOption.CONNECT_TIMEOUT_MILLIS
 *   RequestConfig.responseTimeout  -> HttpClient.responseTimeout
 *   disableAutomaticRetries()      -> HttpClient.disableRetry(true)
 * </pre>
 */
@Configuration(proxyBeanMethods = false)
public class CoreBankingClientConfig {

    public static final String POOL_NAME = "core-banking";

    @Bean(destroyMethod = "close")
    public PoolingHttpClientConnectionManager coreBankingConnectionManager(DownstreamProperties properties) {
        return PoolingHttpClientConnectionManagerBuilder.create()
                .setMaxConnTotal(properties.maxConnections())
                // Un único host downstream: el límite por ruta es el límite total.
                .setMaxConnPerRoute(properties.maxConnections())
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                        .setConnectTimeout(Timeout.of(properties.connectTimeout()))
                        .setSocketTimeout(Timeout.of(properties.responseTimeout()))
                        .build())
                .build();
    }

    @Bean(destroyMethod = "close")
    public CloseableHttpClient coreBankingHttpClient(PoolingHttpClientConnectionManager coreBankingConnectionManager,
                                                     DownstreamProperties properties) {
        return HttpClients.custom()
                .setConnectionManager(coreBankingConnectionManager)
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setConnectionRequestTimeout(Timeout.of(properties.pendingAcquireTimeout()))
                        .setResponseTimeout(Timeout.of(properties.responseTimeout()))
                        .build())
                .evictIdleConnections(TimeValue.of(properties.maxIdleTime()))
                // IMPORTANTE: por defecto Apache reintenta GET ante ciertos IOException y ante 503/429
                // (esperando hasta 1 s). Eso falsearía la comparación con fallos controlados.
                .disableAutomaticRetries()
                .build();
    }

    @Bean
    public RestClient coreBankingRestClient(RestClient.Builder builder, CloseableHttpClient coreBankingHttpClient,
                                            DownstreamProperties properties) {
        return builder
                .baseUrl(properties.baseUrl())
                .requestFactory(new HttpComponentsClientHttpRequestFactory(coreBankingHttpClient))
                .build();
    }

    /** Publica httpcomponents.httpclient.pool.* (conexiones leased/available/pending) en Micrometer. */
    @Bean
    public MeterBinder coreBankingPoolMetrics(PoolingHttpClientConnectionManager coreBankingConnectionManager) {
        return new PoolingHttpClientConnectionManagerMetricsBinder(coreBankingConnectionManager, POOL_NAME);
    }
}
