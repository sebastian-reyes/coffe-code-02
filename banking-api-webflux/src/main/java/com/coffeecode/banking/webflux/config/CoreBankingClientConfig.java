package com.coffeecode.banking.webflux.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;

import io.netty.channel.ChannelOption;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

/**
 * WebClient hacia el core bancario sobre Reactor Netty, con pool y timeouts explícitos.
 *
 * <p>Equivalencias con banking-api-virtual-threads (Apache HttpClient 5):
 * <pre>
 *   maxConnections         -> PoolingHttpClientConnectionManager maxTotal / maxPerRoute
 *   pendingAcquireTimeout  -> RequestConfig.connectionRequestTimeout
 *   connectTimeout         -> ConnectionConfig.connectTimeout
 *   responseTimeout        -> RequestConfig.responseTimeout
 *   disableRetry(true)     -> disableAutomaticRetries()
 * </pre>
 */
@Configuration(proxyBeanMethods = false)
public class CoreBankingClientConfig {

    public static final String POOL_NAME = "core-banking";

    @Bean(destroyMethod = "dispose")
    public ConnectionProvider coreBankingConnectionProvider(DownstreamProperties properties) {
        return ConnectionProvider.builder(POOL_NAME)
                .maxConnections(properties.maxConnections())
                .pendingAcquireTimeout(properties.pendingAcquireTimeout())
                // Cola de espera sin límite de tamaño: solo la limita el tiempo (igual que en Apache HttpClient).
                .pendingAcquireMaxCount(-1)
                .maxIdleTime(properties.maxIdleTime())
                // Publica reactor.netty.connection.provider.* en Micrometer.
                .metrics(true)
                .build();
    }

    @Bean
    public WebClient coreBankingWebClient(WebClient.Builder builder, ConnectionProvider coreBankingConnectionProvider,
                                          DownstreamProperties properties) {
        HttpClient httpClient = HttpClient.create(coreBankingConnectionProvider)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) properties.connectTimeout().toMillis())
                .responseTimeout(properties.responseTimeout())
                // Sin reintentos implícitos: el comportamiento ante fallos debe ser idéntico en ambas APIs.
                .disableRetry(true);

        return builder
                .baseUrl(properties.baseUrl())
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }
}
