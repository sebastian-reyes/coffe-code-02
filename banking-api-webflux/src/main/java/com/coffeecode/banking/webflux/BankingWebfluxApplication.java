package com.coffeecode.banking.webflux;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class BankingWebfluxApplication {

    public static void main(String[] args) {
        SpringApplication.run(BankingWebfluxApplication.class, args);
    }
}
