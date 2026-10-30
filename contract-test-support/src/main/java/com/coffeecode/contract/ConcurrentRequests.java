package com.coffeecode.contract;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;

/**
 * Dispara N peticiones HTTP simultáneas (cliente asíncrono del JDK) y devuelve los status recibidos.
 * Se usa igual en los tests de ambas APIs.
 */
public final class ConcurrentRequests {

    private ConcurrentRequests() {
    }

    public static List<Integer> fire(String url, int count) {
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()) {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).GET().build();
            List<CompletableFuture<Integer>> responses = IntStream.range(0, count)
                    .mapToObj(i -> client.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                            .thenApply(HttpResponse::statusCode))
                    .toList();
            return responses.stream().map(CompletableFuture::join).toList();
        }
    }
}
