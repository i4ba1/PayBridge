package com.paybridge.contracts;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Wire envelope for every payment event (TIS section 10). Payload values are strings so that large numbers
 * never lose precision; no credentials, method secrets or passwords may appear in it.
 */
public record PaymentEventEnvelope(
        UUID eventId,
        String eventType,
        int schemaVersion,
        UUID paymentId,
        UUID merchantId,
        long aggregateVersion,
        Instant occurredAt,
        String correlationId,
        Map<String, String> payload) {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    public PaymentEventEnvelope {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(paymentId, "paymentId");
        Objects.requireNonNull(merchantId, "merchantId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(correlationId, "correlationId");
        if (eventType.isBlank()) {
            throw new IllegalArgumentException("eventType must not be blank");
        }
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("schemaVersion must be >= 1");
        }
        if (aggregateVersion < 1) {
            throw new IllegalArgumentException("aggregateVersion must be >= 1");
        }
        payload = Map.copyOf(Objects.requireNonNull(payload, "payload"));
    }
}
