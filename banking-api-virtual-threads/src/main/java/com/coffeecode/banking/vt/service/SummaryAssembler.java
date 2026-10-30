package com.coffeecode.banking.vt.service;

import java.util.List;

import com.coffeecode.banking.vt.api.FinancialSummary;
import com.coffeecode.banking.vt.client.CoreDtos.CoreBalance;
import com.coffeecode.banking.vt.client.CoreDtos.CoreClient;
import com.coffeecode.banking.vt.client.CoreDtos.CoreMovement;

/**
 * Lógica de negocio pura (sin I/O): combina las tres respuestas del core. Idéntica en ambas APIs.
 */
final class SummaryAssembler {

    static final String MOVEMENTS_UNAVAILABLE = "MOVEMENTS_UNAVAILABLE";

    private SummaryAssembler() {
    }

    /** Resultado de la consulta opcional de movimientos: si falla, el resumen se entrega degradado. */
    record Movements(List<CoreMovement> items, boolean available) {

        static Movements of(List<CoreMovement> items) {
            return new Movements(items, true);
        }

        static Movements unavailable() {
            return new Movements(List.of(), false);
        }
    }

    static FinancialSummary assemble(CoreClient client, CoreBalance balance, Movements movements) {
        var account = new FinancialSummary.Account(
                balance.accountNumber(),
                balance.currency(),
                balance.availableBalance(),
                balance.ledgerBalance(),
                balance.ledgerBalance().subtract(balance.availableBalance()));

        var recent = movements.items().stream()
                .map(m -> new FinancialSummary.Movement(m.movementId(), m.bookingDate(), m.description(), m.amount(), m.type()))
                .toList();

        List<String> warnings = movements.available() ? List.of() : List.of(MOVEMENTS_UNAVAILABLE);

        return new FinancialSummary(client.clientId(), client.fullName(), client.segment(), account, recent, warnings);
    }
}
