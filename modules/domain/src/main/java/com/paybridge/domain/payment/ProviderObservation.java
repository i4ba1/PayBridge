package com.paybridge.domain.payment;

import java.util.Objects;

/**
 * One provider observation. {@code providerRevision} is the simulator operation revision used to order
 * observations (FR-034); null means the adapter has no reliable revision.
 */
public record ProviderObservation(ProviderOutcome outcome, Long providerRevision, String providerReference) {

    public ProviderObservation {
        Objects.requireNonNull(outcome, "outcome");
        if (providerRevision != null && providerRevision < 0) {
            throw new IllegalArgumentException("providerRevision must not be negative");
        }
    }

    public static ProviderObservation of(ProviderOutcome outcome, long providerRevision) {
        return new ProviderObservation(outcome, providerRevision, null);
    }
}
