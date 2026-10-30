package com.coffeecode.mock.web;

import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.coffeecode.mock.chaos.ChaosEngine;
import com.coffeecode.mock.chaos.Decision;
import com.coffeecode.mock.chaos.Endpoint;
import com.coffeecode.mock.data.CoreBankingData;

import reactor.core.publisher.Mono;

/**
 * Endpoints del core bancario simulado.
 *
 * <p>La latencia se aplica con {@code delaySubscription} (un timer de Reactor): no se bloquea
 * ningún hilo del event loop mientras "esperamos" al core.
 */
@RestController
@RequestMapping("/mock")
public class CoreBankingMockController {

    private static final Logger log = LoggerFactory.getLogger(CoreBankingMockController.class);

    private final ChaosEngine chaos;
    private final CoreBankingData data;

    public CoreBankingMockController(ChaosEngine chaos, CoreBankingData data) {
        this.chaos = chaos;
        this.data = data;
    }

    @GetMapping("/clients/{clientId}")
    public Mono<ResponseEntity<Object>> client(@PathVariable String clientId) {
        return respond(Endpoint.CLIENTS, clientId, () -> data.findClient(clientId));
    }

    @GetMapping("/balances/{clientId}")
    public Mono<ResponseEntity<Object>> balance(@PathVariable String clientId) {
        return respond(Endpoint.BALANCES, clientId, () -> data.findBalance(clientId));
    }

    @GetMapping("/movements/{clientId}")
    public Mono<ResponseEntity<Object>> movements(@PathVariable String clientId,
                                                  @RequestParam(defaultValue = "10") int limit) {
        return respond(Endpoint.MOVEMENTS, clientId, () -> data.findMovements(clientId, limit));
    }

    private Mono<ResponseEntity<Object>> respond(Endpoint endpoint, String clientId,
                                                 Supplier<? extends Optional<?>> lookup) {
        Decision decision = chaos.decide(endpoint, clientId);
        log.debug("{} {} -> delay={}ms fail={} ({})", endpoint, clientId,
                decision.delay().toMillis(), decision.fail(), decision.reason());

        return Mono.fromSupplier(() -> decision.fail()
                        ? error(decision.status(), "CORE_UNAVAILABLE", "Fallo simulado del core (" + decision.reason() + ")")
                        : lookup.get()
                                .<ResponseEntity<Object>>map(ResponseEntity::ok)
                                .orElseGet(() -> error(404, "NOT_FOUND", "Cliente " + clientId + " no existe")))
                .delaySubscription(decision.delay());
    }

    private static ResponseEntity<Object> error(int status, String code, String message) {
        return ResponseEntity.status(HttpStatus.valueOf(status)).body(Map.of("code", code, "message", message));
    }
}
