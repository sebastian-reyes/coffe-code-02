package com.coffeecode.contract;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Core bancario falso para tests de integración. Responde datos fijos del cliente {@code C0001}
 * y permite programar, por ruta, el status, el cuerpo y la latencia de cada respuesta.
 *
 * <p>Usa el {@link HttpServer} del JDK con un executor de hilos virtuales, de modo que las
 * respuestas "lentas" no consumen hilos de plataforma.
 */
public final class FakeCoreBanking implements AutoCloseable {

    public static final String CLIENT_PATH = "/mock/clients/";
    public static final String BALANCE_PATH = "/mock/balances/";
    public static final String MOVEMENTS_PATH = "/mock/movements/";

    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, Stub> stubs = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> hits = new ConcurrentHashMap<>();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger maxInFlight = new AtomicInteger();
    private final Set<Integer> clientPorts = ConcurrentHashMap.newKeySet();
    private final List<String> traceparents = new CopyOnWriteArrayList<>();

    static {
        // El HttpServer del JDK cierra las conexiones keep-alive ociosas que superan este límite (200 por
        // defecto). Un cliente con I/O bloqueante (Apache HttpClient) no se entera hasta reutilizarlas y
        // falla con "SocketException"/"NoHttpResponseException": conexión stale. Ver troubleshooting.md, caso 4.
        System.setProperty("sun.net.httpserver.maxIdleConnections", "2000");
    }

    private record Stub(int status, String body, long delayMs) {
    }

    private FakeCoreBanking() {
        try {
            // Backlog explícito: con el valor por defecto del SO (~50 en Windows) una ráfaga de
            // conexiones nuevas recibe "Connection refused" (ver docs/troubleshooting.md, caso 1).
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 1024);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        reset();
        server.start();
    }

    public static FakeCoreBanking start() {
        return new FakeCoreBanking();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** Restaura las respuestas por defecto (200 para C0001, 404 para el resto) y los contadores. */
    public void reset() {
        stubs.clear();
        hits.clear();
        maxInFlight.set(0);
        clientPorts.clear();
        traceparents.clear();
        stub(CLIENT_PATH + "C0001", 200, Contract.fixture("downstream/client-C0001.json"), 0);
        stub(BALANCE_PATH + "C0001", 200, Contract.fixture("downstream/balance-C0001.json"), 0);
        stub(MOVEMENTS_PATH + "C0001", 200, Contract.fixture("downstream/movements-C0001.json"), 0);
        stub("/actuator/health", 200, "{\"status\":\"UP\"}", 0);
    }

    public FakeCoreBanking stub(String path, int status, String body, long delayMs) {
        stubs.put(path, new Stub(status, body, delayMs));
        return this;
    }

    /** Mantiene el cuerpo y status actuales de la ruta pero cambia su latencia. */
    public FakeCoreBanking delay(String path, long delayMs) {
        Stub current = stubs.get(path);
        stubs.put(path, new Stub(current.status(), current.body(), delayMs));
        return this;
    }

    public FakeCoreBanking fail(String path, int status) {
        return stub(path, status, "{\"code\":\"CORE_UNAVAILABLE\",\"message\":\"fallo simulado\"}", 0);
    }

    public int hits(String path) {
        AtomicInteger counter = hits.get(path);
        return counter == null ? 0 : counter.get();
    }

    /** Máximo número de peticiones atendidas simultáneamente desde el último {@link #reset()}. */
    public int maxInFlight() {
        return maxInFlight.get();
    }

    /**
     * Número de conexiones TCP distintas (puertos de origen) vistas desde el último {@link #reset()}.
     * Permite comprobar que el cliente reutiliza las conexiones del pool (keep-alive).
     */
    public int distinctConnections() {
        return clientPorts.size();
    }

    /**
     * Cabeceras W3C {@code traceparent} recibidas desde el último {@link #reset()} (solo rutas /mock/...).
     * Permite comprobar que la traza de la petición entrante llega a cada llamada al core.
     */
    public List<String> traceparents() {
        return List.copyOf(traceparents);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        clientPorts.add(exchange.getRemoteAddress().getPort());
        String traceparent = exchange.getRequestHeaders().getFirst("traceparent");
        if (path.startsWith("/mock/")) {
            traceparents.add(traceparent == null ? "<sin traceparent>" : traceparent);
        }
        hits.computeIfAbsent(path, p -> new AtomicInteger()).incrementAndGet();
        maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
        try {
            Stub stub = stubs.getOrDefault(path, new Stub(404, "{\"code\":\"NOT_FOUND\",\"message\":\"no existe\"}", 0));
            if (stub.delayMs() > 0) {
                Thread.sleep(stub.delayMs());
            }
            byte[] body = stub.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(stub.status(), body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            // El cliente cortó la conexión (timeout/cancelación): es el comportamiento esperado en esos tests.
        } finally {
            inFlight.decrementAndGet();
            exchange.close();
        }
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}
