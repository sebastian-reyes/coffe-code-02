package com.coffeecode.banking.vt.client;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * DTOs de las respuestas del core bancario (lo que devuelve downstream-mock-service).
 */
public final class CoreDtos {

    private CoreDtos() {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CoreClient(String clientId, String fullName, String segment) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CoreBalance(String clientId, String accountNumber, String currency,
                              BigDecimal availableBalance, BigDecimal ledgerBalance) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CoreMovement(String movementId, LocalDate bookingDate, String description,
                               BigDecimal amount, String type) {
    }
}
