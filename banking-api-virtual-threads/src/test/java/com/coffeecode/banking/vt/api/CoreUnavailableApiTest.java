package com.coffeecode.banking.vt.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;

import com.coffeecode.contract.Contract;

/**
 * Core bancario apagado (nadie escucha en el puerto 1): la API responde 503 y el health lo refleja.
 */
@SpringBootTest(properties = {"banking.downstream.base-url=http://127.0.0.1:1", "banking.downstream.connect-timeout=500ms"})
@AutoConfigureMockMvc
class CoreUnavailableApiTest {

    @Autowired
    MockMvc mvc;

    @Test
    void connectionRefusedReturns503() throws Exception {
        mvc.perform(get(Contract.summaryPath("C0001")))
                .andExpect(status().is(503))
                .andExpect(content().json(Contract.fixture("error-downstream-unavailable.json"), JsonCompareMode.STRICT));
    }

    @Test
    void healthReportsCoreBankingDown() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().is(503))
                .andExpect(jsonPath("$.components.coreBanking.status").value("DOWN"));
    }
}
