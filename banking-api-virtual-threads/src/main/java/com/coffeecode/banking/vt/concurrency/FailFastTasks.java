package com.coffeecode.banking.vt.concurrency;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.function.Supplier;

import io.micrometer.context.ContextSnapshotFactory;

/**
 * Lanza tareas en hilos virtuales y espera a que terminen todas, fallando en cuanto la primera
 * falla y cancelando (interrumpiendo) las que sigan en curso. Equivale a {@code Mono.zip} en WebFlux.
 *
 * <p>Es lo que hará {@code StructuredTaskScope} cuando deje de ser preview; aquí se evita usar APIs
 * preview y basta con {@link ExecutorCompletionService} + {@link Future} de Java estándar.
 *
 * <pre>{@code
 * try (var tasks = new FailFastTasks(executor)) {
 *     Supplier<A> a = tasks.fork(() -> callA());
 *     Supplier<B> b = tasks.fork(() -> callB());
 *     tasks.join();               // lanza la primera excepción
 *     return combine(a.get(), b.get());
 * }                               // close(): cancela lo que quede pendiente
 * }</pre>
 */
public final class FailFastTasks implements AutoCloseable {

    private static final ContextSnapshotFactory CONTEXT = ContextSnapshotFactory.builder().build();

    private final CompletionService<Object> completion;
    private final List<Future<Object>> futures = new ArrayList<>();

    public FailFastTasks(ExecutorService executor) {
        this.completion = new ExecutorCompletionService<>(executor);
    }

    /**
     * Lanza la tarea en otro hilo virtual llevándose el contexto del hilo actual (observación/traza,
     * MDC...). Los ThreadLocal NO viajan solos a otro hilo: sin esto, cada llamada al core iniciaría una
     * traza nueva, huérfana (verificado por TracePropagationApiTest). En WebFlux el equivalente es el
     * Reactor Context, que se propaga sin código adicional.
     */
    @SuppressWarnings("unchecked")
    public <T> Supplier<T> fork(Callable<? extends T> task) {
        Callable<Object> withContext = CONTEXT.captureAll().wrap(task::call);
        Future<Object> future = completion.submit(withContext);
        futures.add(future);
        return () -> (T) future.resultNow();
    }

    /** Espera a todas las tareas; si una falla, relanza su excepción sin esperar al resto. */
    public void join() {
        try {
            for (int i = 0; i < futures.size(); i++) {
                Future<Object> done = completion.take();   // la siguiente que termine, en cualquier orden
                if (done.state() == Future.State.FAILED) {
                    throw propagate(done.exceptionNow());
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrumpido esperando las llamadas al core", e);
        }
    }

    /** Cancela las tareas pendientes. {@code cancel(true)} interrumpe el hilo virtual y aborta su I/O. */
    @Override
    public void close() {
        futures.forEach(future -> future.cancel(true));
    }

    private static RuntimeException propagate(Throwable error) {
        if (error instanceof RuntimeException runtime) {
            return runtime;
        }
        if (error instanceof Error fatal) {
            throw fatal;
        }
        return new IllegalStateException(error);
    }
}
