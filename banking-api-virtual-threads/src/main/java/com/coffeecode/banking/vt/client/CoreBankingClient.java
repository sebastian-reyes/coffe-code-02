package com.coffeecode.banking.vt.client;

import java.util.List;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.coffeecode.banking.vt.client.CoreDtos.CoreBalance;
import com.coffeecode.banking.vt.client.CoreDtos.CoreClient;
import com.coffeecode.banking.vt.client.CoreDtos.CoreMovement;

/**
 * Cliente síncrono del core bancario. Cada método bloquea el hilo que lo llama hasta tener la
 * respuesta; en un hilo virtual ese bloqueo es barato: el hilo se "desmonta" del carrier mientras
 * espera el socket y el carrier queda libre para otro trabajo.
 */
@Component
public class CoreBankingClient {

    private static final Logger log = LoggerFactory.getLogger(CoreBankingClient.class);
    private static final ParameterizedTypeReference<List<CoreMovement>> MOVEMENTS = new ParameterizedTypeReference<>() {
    };

    private final RestClient restClient;

    public CoreBankingClient(RestClient coreBankingRestClient) {
        this.restClient = coreBankingRestClient;
    }

    public CoreClient getClient(String clientId) {
        return call("clients", clientId, () -> restClient.get()
                .uri("/mock/clients/{clientId}", clientId)
                .retrieve()
                .body(CoreClient.class));
    }

    public CoreBalance getBalance(String clientId) {
        return call("balances", clientId, () -> restClient.get()
                .uri("/mock/balances/{clientId}", clientId)
                .retrieve()
                .body(CoreBalance.class));
    }

    public List<CoreMovement> getMovements(String clientId, int limit) {
        List<CoreMovement> movements = call("movements", clientId, () -> restClient.get()
                .uri("/mock/movements/{clientId}?limit={limit}", clientId, limit)
                .retrieve()
                .body(MOVEMENTS));
        return movements == null ? List.of() : movements.stream().limit(limit).toList();
    }

    private static <T> T call(String operation, String clientId, Supplier<T> request) {
        log.debug("GET {}/{} en {}", operation, clientId, Thread.currentThread());
        try {
            return request.get();
        } catch (RuntimeException e) {
            throw DownstreamErrors.map(operation, e);
        }
    }
}
