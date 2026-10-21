package com.paybridge.application.payment;

import java.util.List;

/** Outcome of a payment admission. A Rejected result never carries a payment id (FR-015). */
public sealed interface PaymentAdmission {

    record Accepted(String paymentId, String status, long aggregateVersion, boolean replayed) implements PaymentAdmission {}

    record Rejected(Code code, Integer retryAfterSeconds, List<FieldProblem> fieldProblems) implements PaymentAdmission {
        public Rejected {
            fieldProblems = fieldProblems == null ? null : List.copyOf(fieldProblems);
        }

        public static Rejected of(Code code) {
            return new Rejected(code, null, null);
        }

        public static Rejected of(Code code, Integer retryAfterSeconds) {
            return new Rejected(code, retryAfterSeconds, null);
        }
    }

    enum Code { VALIDATION_ERROR, NOT_FOUND, IDEMPOTENCY_CONFLICT, INVOICE_NOT_ELIGIBLE, RATE_LIMITED, TEMPORARILY_UNAVAILABLE }

    record FieldProblem(String field, String code, String message) {}
}
