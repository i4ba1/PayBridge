package com.paybridge.contracts;

import java.util.Set;

/** Event type names. The full catalog with payload fields is in the Data and Messaging Reference. */
public final class PaymentEventTypes {
    public static final String PAYMENT_REQUESTED = "PaymentRequested";
    public static final String PAYMENT_PROCESSING_STARTED = "PaymentProcessingStarted";
    public static final String PAYMENT_PENDING_OBSERVED = "PaymentPendingObserved";
    public static final String PAYMENT_SUCCEEDED = "PaymentSucceeded";
    public static final String PAYMENT_FAILED = "PaymentFailed";
    public static final String PAYMENT_UNKNOWN = "PaymentUnknown";
    public static final String RECONCILIATION_OPENED = "ReconciliationOpened";
    public static final String RECONCILIATION_RESOLVED = "ReconciliationResolved";

    public static final Set<String> ALL = Set.of(
            PAYMENT_REQUESTED, PAYMENT_PROCESSING_STARTED, PAYMENT_PENDING_OBSERVED, PAYMENT_SUCCEEDED,
            PAYMENT_FAILED, PAYMENT_UNKNOWN, RECONCILIATION_OPENED, RECONCILIATION_RESOLVED);

    private PaymentEventTypes() {
    }
}
