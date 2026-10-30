package com.coffeecode.mock;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Simulador del core bancario. Es la única dependencia de las dos APIs que se comparan.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class DownstreamMockApplication {

    public static void main(String[] args) {
        SpringApplication.run(DownstreamMockApplication.class, args);
    }
}
