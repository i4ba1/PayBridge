package com.paybridge.domain.payment;

/** What a provider observation says. Distinct from task state; only SUCCEEDED and DECLINED are authoritative. */
public enum ProviderOutcome {
    SUCCEEDED,
    DECLINED,
    PENDING,
    UNKNOWN
}
