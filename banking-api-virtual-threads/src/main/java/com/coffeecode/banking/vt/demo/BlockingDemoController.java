package com.coffeecode.banking.vt.demo;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * SOLO PARA LA DEMO. Contraparte del endpoint de banking-api-webflux: la misma llamada legada
 * bloqueante (300 ms), aquí sin ningún cuidado especial.
 *
 * <ul>
 *   <li>{@code mode=direct}: se bloquea el hilo virtual de la petición. Es correcto: el hilo virtual
 *       se desmonta y el carrier (ForkJoinPool) queda libre.</li>
 *   <li>{@code mode=synchronized}: el bloqueo ocurre dentro de un bloque {@code synchronized}. Hasta
 *       Java 23 esto "anclaba" (pinning) el hilo virtual a su carrier; desde Java 24 (JEP 491) ya no.
 *       Se puede comprobar con JFR: no aparecen eventos {@code jdk.VirtualThreadPinned}.</li>
 * </ul>
 *
 * Se activa con {@code banking.demo.blocking-endpoints-enabled=true} (perfil {@code demo}).
 */
@RestController
@ConditionalOnBooleanProperty("banking.demo.blocking-endpoints-enabled")
public class BlockingDemoController {

    private static final Logger log = LoggerFactory.getLogger(BlockingDemoController.class);

    @GetMapping("/api/demo/legacy-score/{clientId}")
    public Map<String, Object> legacyScore(@PathVariable String clientId,
                                           @RequestParam(defaultValue = "direct") String mode) throws InterruptedException {
        return switch (mode) {
            case "direct" -> legacyBlockingScore(clientId, mode);
            case "synchronized" -> {
                // Lock por petición para no serializar la demo: solo interesa ver si hay pinning.
                Object lock = new Object();
                synchronized (lock) {
                    yield legacyBlockingScore(clientId, mode);
                }
            }
            default -> throw new IllegalArgumentException("mode debe ser direct o synchronized");
        };
    }

    /** Simula un cliente legado síncrono que tarda 300 ms (p. ej. JDBC o SOAP). */
    private static Map<String, Object> legacyBlockingScore(String clientId, String mode) throws InterruptedException {
        String thread = Thread.currentThread().toString();
        log.info("legacy-score mode={} ejecutándose en {}", mode, thread);
        Thread.sleep(300);
        return Map.of("clientId", clientId, "score", Math.abs(clientId.hashCode() % 1000), "mode", mode,
                "thread", thread, "virtual", Thread.currentThread().isVirtual());
    }
}
