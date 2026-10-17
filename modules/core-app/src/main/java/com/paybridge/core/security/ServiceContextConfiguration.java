package com.paybridge.core.security;

import java.nio.file.Path;
import java.time.Clock;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ServiceContextConfiguration {

    /** The jwks.json entry of Secret paybridge-service-keys, mounted as a file. */
    @Bean
    JwksKeyProvider jwksKeyProvider(@Value("${paybridge.security.service-context.jwks-file:/etc/paybridge/service-keys/jwks.json}") String file,
                                    Clock clock) {
        return new JwksKeyProvider(Path.of(file), clock);
    }

    @Bean
    ServiceContextVerifier serviceContextVerifier(JwksKeyProvider keys, Clock clock,
            @Value("${paybridge.security.service-context.allowed-operations:}") String allowed) {
        Set<String> operations = Arrays.stream(allowed.split(",")).map(String::strip).filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
        return new ServiceContextVerifier(keys, clock, operations);
    }
}
