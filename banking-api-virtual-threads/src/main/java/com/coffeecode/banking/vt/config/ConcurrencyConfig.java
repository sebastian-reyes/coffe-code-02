package com.coffeecode.banking.vt.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Executor para las llamadas concurrentes al core.
 *
 * <p>Es un executor <b>thread-per-task</b> (un hilo virtual nuevo por tarea), no un pool: los hilos
 * virtuales son baratos y no se reutilizan. Hay uno solo para toda la aplicación (no uno por petición)
 * y Spring lo cierra al apagar ({@code close()} espera a que terminen las tareas en curso).
 *
 * <p>Que sea "ilimitado" no significa que los recursos lo sean: el límite real lo pone el pool de
 * conexiones HTTP ({@code banking.downstream.max-connections}) y su timeout de espera.
 */
@Configuration(proxyBeanMethods = false)
public class ConcurrencyConfig {

    @Bean(destroyMethod = "close")
    public ExecutorService coreCallsExecutor() {
        return Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("core-call-", 0).factory());
    }
}
