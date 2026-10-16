package com.paybridge.domain.invoice;

import com.paybridge.domain.money.Money;
import com.paybridge.domain.payment.PaymentStatus;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Invoice with the collection-eligibility rule of FR-009: at most one active or successful collection;
 * a confirmed FAILED payment allows a new operation; UNKNOWN blocks another collection.
 * Amount and currency always come from the invoice, never from the client (FR-007).
 */
public record Invoice(UUID id, UUID merchantId, String reference, Money amount, UUID activePaymentId) {

    private static final int MAX_REFERENCE_LENGTH = 40;

    public Invoice {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(merchantId, "merchantId");
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(amount, "amount");
        if (reference.isBlank() || reference.length() > MAX_REFERENCE_LENGTH) {
            throw new IllegalArgumentException("reference must be 1 to " + MAX_REFERENCE_LENGTH + " characters");
        }
    }

    /**
     * @param activePaymentStatus status of the payment held in {@code activePaymentId}, or null when there is none
     * @return empty when a new collection may start, otherwise why it may not
     */
    public Optional<IneligibleReason> ineligibility(PaymentStatus activePaymentStatus) {
        if (activePaymentStatus == null) {
            return Optional.empty();
        }
        return switch (activePaymentStatus) {
            case FAILED -> Optional.empty();
            case REQUESTED, PROCESSING -> Optional.of(IneligibleReason.ACTIVE_PAYMENT);
            case UNKNOWN -> Optional.of(IneligibleReason.UNCERTAIN_PAYMENT);
            case SUCCEEDED -> Optional.of(IneligibleReason.ALREADY_PAID);
        };
    }
}
