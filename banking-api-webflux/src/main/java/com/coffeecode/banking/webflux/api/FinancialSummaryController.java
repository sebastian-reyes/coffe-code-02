package com.coffeecode.banking.webflux.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.coffeecode.banking.webflux.service.FinancialSummaryService;

import jakarta.validation.constraints.Pattern;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/clients")
public class FinancialSummaryController {

    public static final String CLIENT_ID_PATTERN = "^C\\d{4}$";

    private final FinancialSummaryService service;

    public FinancialSummaryController(FinancialSummaryService service) {
        this.service = service;
    }

    /** La validación de {@code @Pattern} la aplica Spring (method validation) antes de invocar el método. */
    @GetMapping("/{clientId}/financial-summary")
    public Mono<FinancialSummary> financialSummary(@PathVariable @Pattern(regexp = CLIENT_ID_PATTERN) String clientId) {
        return service.getSummary(clientId);
    }
}
