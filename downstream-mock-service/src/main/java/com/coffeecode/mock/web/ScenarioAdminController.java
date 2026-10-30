package com.coffeecode.mock.web;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.coffeecode.mock.chaos.ChaosEngine;
import com.coffeecode.mock.chaos.Scenario;

import java.util.Map;

/**
 * Cambia el escenario del simulador en caliente (lo usan los scripts de carga y la demo).
 * Solo existe si {@code mock.admin-enabled=true}.
 */
@RestController
@RequestMapping("/mock/admin/scenario")
@ConditionalOnBooleanProperty(name = "mock.admin-enabled", matchIfMissing = true)
public class ScenarioAdminController {

    private final ChaosEngine chaos;

    public ScenarioAdminController(ChaosEngine chaos) {
        this.chaos = chaos;
    }

    @GetMapping
    public Scenario current() {
        return chaos.current();
    }

    @PutMapping
    public Scenario update(@RequestBody Scenario scenario) {
        return chaos.update(scenario);
    }

    @PostMapping("/reset")
    public Scenario reset() {
        return chaos.reset();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> invalid(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("code", "INVALID_SCENARIO", "message", e.getMessage()));
    }
}
