package com.paybridge.application.port;

import com.paybridge.domain.payment.ProviderObservation;
import java.time.Duration;

/**
 * Port to the payment provider. Every call ends in exactly one {@link ProviderResult}; an exception never carries a
 * business meaning. Implementations perform no SQL and never retry a charge under another key (API contract 8.5).
 *
 * <p>Guarantees of the simulator are not guarantees of a real provider. A real adapter must document its own
 * idempotency window, ordering and lookup semantics before it implements this port (FR-031,
 * docs/runbooks/provider-integration.md).
 */
public interface BillingProvider {

    /** Creates the charge, or returns the existing one for the same provider key and identical immutable fields. */
    ProviderResult charge(ChargeRequest request);

    /** Current authoritative state for a provider key. {@link ProviderResult.Kind#NOT_FOUND} is uncertainty, not a decline. */
    ProviderResult lookup(LookupRequest request);

    /** The provider key of a payment: {@code pbk-<paymentId>} (decision D-28). Identical on every attempt. */
    static String providerKey(String paymentId) {
        return "pbk-" + paymentId;
    }

    record ChargeRequest(String paymentId, String merchantId, String invoiceId, long amountMinor, String currency,
                         String methodToken, String scenarioRunId, String correlationId) {
        public String providerKey() {
            return BillingProvider.providerKey(paymentId);
        }

        @Override
        public String toString() {
            return "ChargeRequest[paymentId=" + paymentId + ", amountMinor=" + amountMinor + ", currency=" + currency + "]";
        }
    }

    /** The expected amount and currency let the adapter reject a lookup that answers about something else. */
    record LookupRequest(String providerKey, long expectedAmountMinor, String expectedCurrency, Long highestKnownRevision,
                         String correlationId) {}

    /** What happened, in the vocabulary of the payment_attempt table. */
    enum ErrorClass { NONE, RETRYABLE_UNAVAILABLE, TRANSPORT_UNCERTAIN, MALFORMED }

    /**
     * @param kind          OBSERVED carries an observation; the other kinds carry none
     * @param errorClass    NONE for OBSERVED and for the business kinds; otherwise why the call is not evidence
     * @param observation   present for OBSERVED only
     * @param declineCode   present for a DECLINED observation
     * @param retryAfter    from the Retry-After header of a retryable answer, else null
     * @param httpStatus    the HTTP status when a response was received, else null
     * @param detail        a short sanitized reason for logs and investigation notes; never a body or a credential
     */
    record ProviderResult(Kind kind, ErrorClass errorClass, ProviderObservation observation, String declineCode,
                          Duration retryAfter, Integer httpStatus, String detail) {

        public enum Kind {
            /** Authoritative or pending answer in {@code observation}. */
            OBSERVED,
            /** 409 idempotency_conflict: investigation, never a new key. */
            IDEMPOTENCY_CONFLICT,
            /** 422 SCENARIO_NOT_AVAILABLE: no charge exists; the payment becomes UNKNOWN with a case. */
            SCENARIO_NOT_AVAILABLE,
            /** 400 or 401: our bug or configuration; the charge was not created. */
            CLIENT_ERROR,
            /** Lookup 404: not found at this time. */
            NOT_FOUND,
            /** The call produced no usable evidence; see errorClass. */
            NO_EVIDENCE
        }

        public static ProviderResult observed(ProviderObservation observation, String declineCode, int httpStatus) {
            return new ProviderResult(Kind.OBSERVED, ErrorClass.NONE, observation, declineCode, null, httpStatus, null);
        }

        public static ProviderResult noEvidence(ErrorClass errorClass, Duration retryAfter, Integer httpStatus, String detail) {
            return new ProviderResult(Kind.NO_EVIDENCE, errorClass, null, null, retryAfter, httpStatus, detail);
        }

        public static ProviderResult of(Kind kind, int httpStatus, String detail) {
            return new ProviderResult(kind, ErrorClass.NONE, null, null, null, httpStatus, detail);
        }

        public boolean isObserved() {
            return kind == Kind.OBSERVED;
        }
    }
}
