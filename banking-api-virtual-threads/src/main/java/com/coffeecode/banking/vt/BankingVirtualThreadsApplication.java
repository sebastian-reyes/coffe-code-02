package com.coffeecode.banking.vt;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class BankingVirtualThreadsApplication {

    public static void main(String[] args) {
        SpringApplication.run(BankingVirtualThreadsApplication.class, args);
    }
}
