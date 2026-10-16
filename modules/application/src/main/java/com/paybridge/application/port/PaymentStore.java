package com.paybridge.application.port;

import java.time.Instant;
import java.util.Optional;

/** Persistence port for payments. The initial version only reads; admission and transitions arrive in T07 and T08. */
public interface PaymentStore {

    Optional<PaymentRecord> findById(String id);

    /** The single non-FAILED payment of an invoice, if any (the ux_payment_one_active guarantee). */
    Optional<PaymentRecord> findBlockingPayment(String invoiceId);

    long countByMerchant(String merchantId);

    record PaymentRecord(String id, String merchantId, String invoiceId, String methodCode, long amountMinor,
                         String currency, String status, long aggregateVersion, String providerKey,
                         String providerReference, String scenarioRunId, String correlationId, String requestedBy,
                         Instant createdAt, Instant completedAt) {}
}
