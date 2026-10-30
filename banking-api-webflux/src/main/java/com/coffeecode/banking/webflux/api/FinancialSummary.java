package com.coffeecode.banking.webflux.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Contrato de respuesta de {@code GET /api/clients/{clientId}/financial-summary}. Idéntico en ambas APIs.
 */
public record FinancialSummary(
        String clientId,
        String fullName,
        String segment,
        Account account,
        List<Movement> recentMovements,
        List<String> warnings) {

    public record Account(String accountNumber, String currency, BigDecimal availableBalance,
                          BigDecimal ledgerBalance, BigDecimal heldAmount) {
    }

    public record Movement(String movementId, LocalDate bookingDate, String description,
                           BigDecimal amount, String type) {
    }
}
