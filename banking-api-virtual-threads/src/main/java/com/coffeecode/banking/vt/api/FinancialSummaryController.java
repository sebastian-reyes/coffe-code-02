package com.coffeecode.banking.vt.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.coffeecode.banking.vt.service.FinancialSummaryService;

import jakarta.validation.constraints.Pattern;

@RestController
@RequestMapping("/api/clients")
public class FinancialSummaryController {

    public static final String CLIENT_ID_PATTERN = "^C\\d{4}$";

    private final FinancialSummaryService service;

    public FinancialSummaryController(FinancialSummaryService service) {
        this.service = service;
    }

    /**
     * Con {@code spring.threads.virtual.enabled=true} Tomcat atiende cada petición en un hilo virtual,
     * así que este método puede bloquear sin agotar un pool de hilos de plataforma.
     */
    @GetMapping("/{clientId}/financial-summary")
    public FinancialSummary financialSummary(@PathVariable @Pattern(regexp = CLIENT_ID_PATTERN) String clientId) {
        return service.getSummary(clientId);
    }
}
