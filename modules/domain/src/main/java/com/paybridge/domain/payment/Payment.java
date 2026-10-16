package com.paybridge.domain.payment;

import com.paybridge.domain.money.Money;
import java.util.Objects;
import java.util.UUID;

/**
 * Payment lifecycle rules. Legal moves: REQUESTED to PROCESSING; PROCESSING to SUCCEEDED, FAILED or UNKNOWN;
 * UNKNOWN to SUCCEEDED or FAILED. SUCCEEDED and FAILED are terminal and never regress. Only authoritative
 * provider evidence produces SUCCEEDED or FAILED; a timeout alone yields UNKNOWN (FR-017).
 */
public final class Payment {

    private final UUID id;
    private final UUID merchantId;
    private final UUID invoiceId;
    private final Money amount;
    private PaymentStatus status;
    private long aggregateVersion;
    private Long lastProviderRevision;

    private Payment(UUID id, UUID merchantId, UUID invoiceId, Money amount, PaymentStatus status,
            long aggregateVersion, Long lastProviderRevision) {
        this.id = Objects.requireNonNull(id, "id");
        this.merchantId = Objects.requireNonNull(merchantId, "merchantId");
        this.invoiceId = Objects.requireNonNull(invoiceId, "invoiceId");
        this.amount = Objects.requireNonNull(amount, "amount");
        this.status = Objects.requireNonNull(status, "status");
        if (aggregateVersion < 1) {
            throw new IllegalArgumentException("aggregateVersion must be >= 1");
        }
        this.aggregateVersion = aggregateVersion;
        this.lastProviderRevision = lastProviderRevision;
    }

    /** A newly admitted payment: REQUESTED at aggregate version 1. */
    public static Payment request(UUID id, UUID merchantId, UUID invoiceId, Money amount) {
        return new Payment(id, merchantId, invoiceId, amount, PaymentStatus.REQUESTED, 1, null);
    }

    /** Rebuilds a payment from persisted state; used by adapters. */
    public static Payment restore(UUID id, UUID merchantId, UUID invoiceId, Money amount, PaymentStatus status,
            long aggregateVersion, Long lastProviderRevision) {
        return new Payment(id, merchantId, invoiceId, amount, status, aggregateVersion, lastProviderRevision);
    }

    /** REQUESTED to PROCESSING when the executor begins attempt 1. */
    public TransitionResult startProcessing() {
        if (status != PaymentStatus.REQUESTED) {
            throw new IllegalTransitionException("Cannot start processing from " + status);
        }
        return move(PaymentStatus.PROCESSING);
    }

    /** Applies one provider observation. Never regresses a terminal payment. */
    public TransitionResult transition(ProviderObservation observation) {
        Objects.requireNonNull(observation, "observation");
        if (status == PaymentStatus.REQUESTED) {
            throw new IllegalTransitionException(
                    "Cannot apply " + observation.outcome() + " before processing has started");
        }
        Long revision = observation.providerRevision();
        if (revision != null && lastProviderRevision != null && revision < lastProviderRevision) {
            return new TransitionResult.Ignored(TransitionResult.Reason.OLDER_REVISION);
        }
        TransitionResult result = status.isTerminal() ? applyToTerminal(observation) : applyToOpen(observation);
        if (!(result instanceof TransitionResult.ConflictingTerminalEvidence)
                && revision != null && (lastProviderRevision == null || revision > lastProviderRevision)) {
            lastProviderRevision = revision;
        }
        return result;
    }

    private TransitionResult applyToOpen(ProviderObservation observation) {
        return switch (observation.outcome()) {
            case SUCCEEDED -> move(PaymentStatus.SUCCEEDED);
            case DECLINED -> move(PaymentStatus.FAILED);
            case UNKNOWN -> status == PaymentStatus.PROCESSING
                    ? move(PaymentStatus.UNKNOWN)
                    : new TransitionResult.Ignored(TransitionResult.Reason.NO_STATE_CHANGE);
            case PENDING -> new TransitionResult.Ignored(TransitionResult.Reason.NO_STATE_CHANGE);
        };
    }

    private TransitionResult applyToTerminal(ProviderObservation observation) {
        PaymentStatus implied = switch (observation.outcome()) {
            case SUCCEEDED -> PaymentStatus.SUCCEEDED;
            case DECLINED -> PaymentStatus.FAILED;
            case PENDING, UNKNOWN -> null;
        };
        if (implied == null) {
            return new TransitionResult.Ignored(TransitionResult.Reason.NON_AUTHORITATIVE);
        }
        if (implied == status) {
            return new TransitionResult.Ignored(TransitionResult.Reason.DUPLICATE);
        }
        return new TransitionResult.ConflictingTerminalEvidence(status, observation.outcome(),
                observation.providerRevision());
    }

    private TransitionResult move(PaymentStatus to) {
        PaymentStatus from = status;
        status = to;
        aggregateVersion++;
        return new TransitionResult.Applied(from, to, aggregateVersion);
    }

    public UUID id() {
        return id;
    }

    public UUID merchantId() {
        return merchantId;
    }

    public UUID invoiceId() {
        return invoiceId;
    }

    public Money amount() {
        return amount;
    }

    public PaymentStatus status() {
        return status;
    }

    public long aggregateVersion() {
        return aggregateVersion;
    }

    public Long lastProviderRevision() {
        return lastProviderRevision;
    }
}
