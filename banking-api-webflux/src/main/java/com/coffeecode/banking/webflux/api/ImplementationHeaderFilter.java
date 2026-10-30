package com.coffeecode.banking.webflux.api;

import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import reactor.core.publisher.Mono;

/**
 * Añade {@code X-Implementation: webflux} a todas las respuestas. Permite saber qué API respondió
 * sin introducir diferencias en el cuerpo JSON.
 */
@Component
public class ImplementationHeaderFilter implements WebFilter {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        exchange.getResponse().getHeaders().set("X-Implementation", "webflux");
        return chain.filter(exchange);
    }
}
