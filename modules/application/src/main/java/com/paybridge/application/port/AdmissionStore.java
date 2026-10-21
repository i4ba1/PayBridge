package com.paybridge.application.port;

import java.util.Optional;

/** Persistence port for payment admission (DMR 6.1). One call to {@link #admit} is one database transaction. */
public interface AdmissionStore {

    /** Autocommit read of an earlier request with this key in this merchant. */
    Optional<Replay> findReplay(String merchantId, String idempotencyKey);

    /** The method behind an allow-listed token, only when the token, the method and the merchant's method are enabled. */
    Optional<MethodChoice> resolveMethod(String merchantId, String token);

    AdmissionResult admit(AdmissionRequest request);

    record Replay(String paymentId, String requestHash, String status, long aggregateVersion) {}

    record MethodChoice(String methodCode) {}

    record AdmissionRequest(String paymentId, String eventId, String merchantId, String userId, String invoiceId,
                            String idempotencyKey, String requestHash, String methodCode, String methodToken,
                            String scenarioRunId, String correlationId, int lockWaitTimeoutSeconds) {}

    sealed interface AdmissionResult {
        record Created(String paymentId) implements AdmissionResult {}

        /** Another request with the same key committed first; its state is returned. */
        record Duplicate(Replay existing) implements AdmissionResult {}

        record InvoiceNotFound() implements AdmissionResult {}

        record NotEligible() implements AdmissionResult {}

        /** Deadlock or lock wait timeout. Nothing was written. */
        record Transient() implements AdmissionResult {}
    }
}
