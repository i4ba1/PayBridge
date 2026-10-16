package com.paybridge.domain.payment;

/** What happened when an observation or start request was applied to a payment. */
public sealed interface TransitionResult {

    /** The status changed; aggregateVersion advanced to {@code newVersion}. */
    record Applied(PaymentStatus from, PaymentStatus to, long newVersion) implements TransitionResult {
    }

    /** Nothing changed. */
    record Ignored(Reason reason) implements TransitionResult {
    }

    /**
     * Authoritative evidence contradicts a terminal state. State is unchanged and the caller must open an
     * investigation (FR-018).
     */
    record ConflictingTerminalEvidence(PaymentStatus current, ProviderOutcome observed, Long providerRevision)
            implements TransitionResult {
    }

    enum Reason {
        /** Observation revision is older than one already seen. */
        OLDER_REVISION,
        /** Same authoritative result as the terminal state already held. */
        DUPLICATE,
        /** PENDING or UNKNOWN cannot move a terminal payment. */
        NON_AUTHORITATIVE,
        /** Valid observation that leaves the status as it is (PENDING, or UNKNOWN while UNKNOWN). */
        NO_STATE_CHANGE
    }
}
