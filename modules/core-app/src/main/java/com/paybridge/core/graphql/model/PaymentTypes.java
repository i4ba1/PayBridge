package com.paybridge.core.graphql.model;

import java.time.Instant;
import java.util.List;

/** Java shapes of the payment part of the schema. Record names equal the GraphQL type names so DGS resolves unions. */
public final class PaymentTypes {
    private PaymentTypes() {}

    public record Payment(String id, String merchantId, String invoiceId, String status, String amountMinor,
                          String currency, String methodCode, String version, String providerReference, Instant createdAt,
                          Instant updatedAt, List<Object> attempts, Object uncertainty) {}

    public record PaymentAccepted(Payment payment, boolean replayed) {}

    public record RequestPaymentInput(String invoiceId, String methodToken, String idempotencyKey) {}
}
