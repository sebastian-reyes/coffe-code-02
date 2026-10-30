package com.coffeecode.banking.webflux.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import com.coffeecode.banking.webflux.client.CoreDtos.CoreBalance;
import com.coffeecode.banking.webflux.client.CoreDtos.CoreClient;
import com.coffeecode.banking.webflux.client.CoreDtos.CoreMovement;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuple2;

/**
 * Cliente reactivo del core bancario. Ningún método bloquea: devuelven publishers "fríos" que
 * solo hacen la petición HTTP cuando alguien se suscribe.
 */
@Component
public class CoreBankingClient {

    private static final Logger log = LoggerFactory.getLogger(CoreBankingClient.class);

    private final WebClient webClient;

    public CoreBankingClient(WebClient coreBankingWebClient) {
        this.webClient = coreBankingWebClient;
    }

    public Mono<CoreClient> getClient(String clientId) {
        return webClient.get()
                .uri("/mock/clients/{clientId}", clientId)
                .retrieve()
                .bodyToMono(CoreClient.class)
                .doOnSubscribe(s -> log.debug("GET clients/{} en {}", clientId, Thread.currentThread()))
                .onErrorMap(e -> DownstreamErrors.map("clients", e));
    }

    public Mono<CoreBalance> getBalance(String clientId) {
        return webClient.get()
                .uri("/mock/balances/{clientId}", clientId)
                .retrieve()
                .bodyToMono(CoreBalance.class)
                .doOnSubscribe(s -> log.debug("GET balances/{} en {}", clientId, Thread.currentThread()))
                .onErrorMap(e -> DownstreamErrors.map("balances", e));
    }

    /**
     * Los movimientos llegan como un array JSON que Jackson decodifica elemento a elemento en un Flux.
     *
     * <p>OJO: no usar {@code take(limit)} aquí. {@code take} cancela el Flux en cuanto recibe el
     * elemento N, antes de terminar de leer el cuerpo, y Reactor Netty cierra una conexión cancelada
     * en vez de devolverla al pool. Con 300 req/s eso abría ~300 conexiones nuevas por segundo y agotaba
     * los puertos efímeros (BindException). Ver docs/troubleshooting.md, caso 4.
     * Se limita con un filtro, que consume el cuerpo completo sin cancelar.
     */
    public Flux<CoreMovement> getMovements(String clientId, int limit) {
        return webClient.get()
                .uri("/mock/movements/{clientId}?limit={limit}", clientId, limit)
                .retrieve()
                .bodyToFlux(CoreMovement.class)
                .index()
                .filter(indexed -> indexed.getT1() < limit)
                .map(Tuple2::getT2)
                .doOnSubscribe(s -> log.debug("GET movements/{} en {}", clientId, Thread.currentThread()))
                .onErrorMap(e -> DownstreamErrors.map("movements", e));
    }
}
