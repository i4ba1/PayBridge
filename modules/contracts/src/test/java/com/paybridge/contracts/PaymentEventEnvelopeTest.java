package com.paybridge.contracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PaymentEventEnvelopeTest {

    private static PaymentEventEnvelope envelope(long version, Map<String, String> payload) {
        return new PaymentEventEnvelope(UUID.randomUUID(), PaymentEventTypes.PAYMENT_REQUESTED, 1, UUID.randomUUID(),
                UUID.randomUUID(), version, Instant.parse("2026-10-16T02:00:00Z"), "demo-run-001", payload);
    }

    @Test
    void copiesPayloadDefensively() {
        Map<String, String> payload = new HashMap<>(Map.of("amountMinor", "150000"));
        PaymentEventEnvelope e = envelope(1, payload);
        payload.put("tamper", "x");
        assertThat(e.payload()).containsOnlyKeys("amountMinor");
        assertThatThrownBy(() -> e.payload().put("x", "y")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsInvalidVersions() {
        assertThatThrownBy(() -> envelope(0, Map.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PaymentEventEnvelope(UUID.randomUUID(), "PaymentRequested", 0, UUID.randomUUID(),
                UUID.randomUUID(), 1, Instant.now(), "c", Map.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void typeCatalogContainsRequested() {
        assertThat(PaymentEventTypes.ALL).hasSize(8).contains(PaymentEventTypes.PAYMENT_REQUESTED);
    }

    @Test
    void graphqlSchemaIsPackagedAndDeclaresAdmission() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/graphql/paybridge.graphqls")) {
            String sdl = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(sdl).contains("requestPayment(input: RequestPaymentInput!)").contains("directive @auth");
        }
    }
}
