package com.coffeecode.contract;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Acceso a los JSON de contrato ({@code src/main/resources/contract}). Los mismos ficheros validan
 * las respuestas de banking-api-webflux y de banking-api-virtual-threads.
 */
public final class Contract {

    public static final String SUMMARY_PATH = "/api/clients/%s/financial-summary";

    private Contract() {
    }

    public static String fixture(String name) {
        try (InputStream in = Contract.class.getResourceAsStream("/contract/" + name)) {
            if (in == null) {
                throw new IllegalArgumentException("No existe el fixture de contrato: " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static String summaryPath(String clientId) {
        return SUMMARY_PATH.formatted(clientId);
    }
}
