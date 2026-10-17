package com.paybridge.simulator.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.paybridge.simulator.service.WebhookEvents;
import java.io.IOException;
import java.time.Instant;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Timestamps are ISO-8601 UTC with milliseconds in every response (API contract 5.2 and 8.2). */
@Configuration(proxyBeanMethods = false)
public class JsonConfiguration {
    @Bean
    SimpleModule millisecondInstants() {
        var module = new SimpleModule("millisecond-instants");
        module.addSerializer(Instant.class, new JsonSerializer<>() {
            @Override
            public void serialize(Instant value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
                gen.writeString(WebhookEvents.iso(value));
            }
        });
        return module;
    }
}
