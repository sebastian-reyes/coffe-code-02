package com.coffeecode.banking.webflux.demo;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.coffeecode.banking.webflux.client.CoreBankingClient;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * SOLO PARA LA DEMO / TROUBLESHOOTING. Muestra qué ocurre cuando una dependencia bloqueante
 * (un driver JDBC, un SDK SOAP, un cliente legado...) se usa dentro de WebFlux.
 *
 * <ul>
 *   <li>{@code mode=event-loop}: ANTIPATRÓN. El {@code Thread.sleep} corre en un hilo del event loop
 *       ({@code reactor-http-nio-*} / {@code webflux-http-nio-*}). No falla, pero lo bloquea: bajo carga la latencia
 *       de TODAS las peticiones se dispara.</li>
 *   <li>{@code mode=block}: ANTIPATRÓN. Llamar a {@code .block()} dentro del event loop. Reactor lo
 *       detecta y lanza {@code IllegalStateException: block()/blockFirst()/blockLast() are blocking...}.</li>
 *   <li>{@code mode=offload} (recomendado): la llamada bloqueante se aísla en
 *       {@code Schedulers.boundedElastic()} y el event loop queda libre.</li>
 * </ul>
 *
 * Se activa con {@code banking.demo.blocking-endpoints-enabled=true} (perfil {@code demo}).
 */
@RestController
@ConditionalOnBooleanProperty("banking.demo.blocking-endpoints-enabled")
public class BlockingDemoController {

    private static final Logger log = LoggerFactory.getLogger(BlockingDemoController.class);

    private final CoreBankingClient core;

    public BlockingDemoController(CoreBankingClient core) {
        this.core = core;
    }

    @GetMapping("/api/demo/legacy-score/{clientId}")
    public Mono<Map<String, Object>> legacyScore(@PathVariable String clientId,
                                                 @RequestParam(defaultValue = "offload") String mode) {
        return switch (mode) {
            case "event-loop" -> Mono.fromCallable(() -> legacyBlockingScore(clientId, mode));
            case "block" -> Mono.fromCallable(() -> {
                // Antipatrón: block() en un hilo no bloqueante -> IllegalStateException
                var client = core.getClient(clientId).block();
                return Map.<String, Object>of("clientId", client.clientId(), "mode", mode,
                        "thread", Thread.currentThread().toString());
            });
            case "offload" -> Mono.fromCallable(() -> legacyBlockingScore(clientId, mode))
                    .subscribeOn(Schedulers.boundedElastic());
            default -> Mono.error(new IllegalArgumentException("mode debe ser event-loop, block u offload"));
        };
    }

    /** Simula un cliente legado síncrono que tarda 300 ms (p. ej. JDBC o SOAP). */
    private static Map<String, Object> legacyBlockingScore(String clientId, String mode) throws InterruptedException {
        String thread = Thread.currentThread().toString();
        log.info("legacy-score mode={} ejecutándose en {}", mode, thread);
        Thread.sleep(300);
        return Map.of("clientId", clientId, "score", Math.abs(clientId.hashCode() % 1000), "mode", mode, "thread", thread);
    }
}
