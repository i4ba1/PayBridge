package com.paybridge.simulator;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Independent provider simulator with its own schema, account and Flyway history. */
@SpringBootApplication
@EnableScheduling
public class SimulatorApplication {
    public static void main(String[] args) {
        // The settings file is named simulator.yaml, not application.yaml, so it can never be shadowed by another
        // module's application.yaml when both are on one classpath (the integration tests).
        new SpringApplicationBuilder(SimulatorApplication.class).properties("spring.config.name=simulator").run(args);
    }
}
