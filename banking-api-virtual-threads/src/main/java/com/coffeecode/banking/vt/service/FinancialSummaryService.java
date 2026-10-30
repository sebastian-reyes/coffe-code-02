package com.coffeecode.banking.vt.service;

import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.coffeecode.banking.vt.api.FinancialSummary;
import com.coffeecode.banking.vt.client.CoreBankingClient;
import com.coffeecode.banking.vt.client.CoreDtos.CoreBalance;
import com.coffeecode.banking.vt.client.CoreDtos.CoreClient;
import com.coffeecode.banking.vt.concurrency.FailFastTasks;
import com.coffeecode.banking.vt.config.DownstreamProperties;
import com.coffeecode.banking.vt.error.DownstreamException;
import com.coffeecode.banking.vt.service.SummaryAssembler.Movements;

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
    private final ExecutorService executor;
    private final int movementsLimit;

    public FinancialSummaryService(CoreBankingClient core, ExecutorService coreCallsExecutor,
                                   DownstreamProperties properties) {
        this.core = core;
        this.executor = coreCallsExecutor;
        this.movementsLimit = properties.movementsLimit();
    }

    public FinancialSummary getSummary(String clientId) {
        try (var tasks = new FailFastTasks(executor)) {
            Supplier<CoreClient> client = tasks.fork(() -> core.getClient(clientId));
            Supplier<CoreBalance> balance = tasks.fork(() -> core.getBalance(clientId));
            Supplier<Movements> movements = tasks.fork(() -> movementsOrDegraded(clientId));

            tasks.join();

            return SummaryAssembler.assemble(client.get(), balance.get(), movements.get());
        }
    }

    private Movements movementsOrDegraded(String clientId) {
        try {
            return Movements.of(core.getMovements(clientId, movementsLimit));
        } catch (DownstreamException e) {
            log.warn("Movimientos no disponibles para {} ({}): se responde degradado", clientId, e.code());
            return Movements.unavailable();
        }
    }
}
