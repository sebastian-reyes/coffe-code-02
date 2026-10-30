package com.coffeecode.banking.webflux.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.coffeecode.banking.webflux.api.FinancialSummary;
import com.coffeecode.banking.webflux.client.CoreBankingClient;
import com.coffeecode.banking.webflux.config.DownstreamProperties;
import com.coffeecode.banking.webflux.error.DownstreamException;
import com.coffeecode.banking.webflux.service.SummaryAssembler.Movements;

import reactor.core.publisher.Mono;

/**
 * Política de concurrencia (idéntica en ambas APIs):
 * <ul>
 *   <li>cliente, saldo y movimientos se consultan <b>a la vez</b>;</li>
 *   <li>cliente y saldo son obligatorios: si uno falla, la petición falla de inmediato y se
 *       <b>cancelan</b> las llamadas que sigan en curso (fail-fast);</li>
 *   <li>movimientos es opcional: si falla, se responde 200 con {@code warnings=[MOVEMENTS_UNAVAILABLE]}.</li>
 * </ul>
 */
@Service
public class FinancialSummaryService {

    private static final Logger log = LoggerFactory.getLogger(FinancialSummaryService.class);

    private final CoreBankingClient core;
    private final int movementsLimit;

    public FinancialSummaryService(CoreBankingClient core, DownstreamProperties properties) {
        this.core = core;
        this.movementsLimit = properties.movementsLimit();
    }

    public Mono<FinancialSummary> getSummary(String clientId) {
        Mono<Movements> movements = core.getMovements(clientId, movementsLimit)
                .collectList()
                .map(Movements::of)
                .onErrorResume(DownstreamException.class, e -> {
                    log.warn("Movimientos no disponibles para {} ({}): se responde degradado", clientId, e.code());
                    return Mono.just(Movements.unavailable());
                });
        return Mono.zip(core.getClient(clientId), core.getBalance(clientId), movements)
                .map(t -> SummaryAssembler.assemble(t.getT1(), t.getT2(), t.getT3()));
    }
}
