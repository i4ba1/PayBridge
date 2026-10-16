package com.paybridge.domain.payment;

import static com.paybridge.domain.payment.PaymentStatus.FAILED;
import static com.paybridge.domain.payment.PaymentStatus.PROCESSING;
import static com.paybridge.domain.payment.PaymentStatus.REQUESTED;
import static com.paybridge.domain.payment.PaymentStatus.SUCCEEDED;
import static com.paybridge.domain.payment.PaymentStatus.UNKNOWN;
import static com.paybridge.domain.payment.ProviderOutcome.DECLINED;
import static com.paybridge.domain.payment.ProviderOutcome.PENDING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.paybridge.domain.money.CurrencyCode;
import com.paybridge.domain.money.Money;
import com.paybridge.domain.payment.TransitionResult.Applied;
import com.paybridge.domain.payment.TransitionResult.ConflictingTerminalEvidence;
import com.paybridge.domain.payment.TransitionResult.Ignored;
import com.paybridge.domain.payment.TransitionResult.Reason;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

class PaymentTransitionTest {

    private static Payment in(PaymentStatus status, Long revision) {
        return Payment.restore(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                new Money(150_000, CurrencyCode.IDR), status, 3, revision);
    }

    private static Payment in(PaymentStatus status) {
        return in(status, null);
    }

    private static ProviderObservation obs(ProviderOutcome outcome, Long revision) {
        return new ProviderObservation(outcome, revision, null);
    }

    /** Every (status, outcome) pair except REQUESTED, which is illegal for all outcomes. */
    static Stream<Arguments> matrix() {
        return Stream.of(
                // PROCESSING
                Arguments.of(PROCESSING, ProviderOutcome.SUCCEEDED, SUCCEEDED, true, null),
                Arguments.of(PROCESSING, DECLINED, FAILED, true, null),
                Arguments.of(PROCESSING, ProviderOutcome.UNKNOWN, UNKNOWN, true, null),
                Arguments.of(PROCESSING, PENDING, PROCESSING, false, Reason.NO_STATE_CHANGE),
                // UNKNOWN
                Arguments.of(UNKNOWN, ProviderOutcome.SUCCEEDED, SUCCEEDED, true, null),
                Arguments.of(UNKNOWN, DECLINED, FAILED, true, null),
                Arguments.of(UNKNOWN, ProviderOutcome.UNKNOWN, UNKNOWN, false, Reason.NO_STATE_CHANGE),
                Arguments.of(UNKNOWN, PENDING, UNKNOWN, false, Reason.NO_STATE_CHANGE),
                // SUCCEEDED is terminal
                Arguments.of(SUCCEEDED, ProviderOutcome.SUCCEEDED, SUCCEEDED, false, Reason.DUPLICATE),
                Arguments.of(SUCCEEDED, PENDING, SUCCEEDED, false, Reason.NON_AUTHORITATIVE),
                Arguments.of(SUCCEEDED, ProviderOutcome.UNKNOWN, SUCCEEDED, false, Reason.NON_AUTHORITATIVE),
                // FAILED is terminal
                Arguments.of(FAILED, DECLINED, FAILED, false, Reason.DUPLICATE),
                Arguments.of(FAILED, PENDING, FAILED, false, Reason.NON_AUTHORITATIVE),
                Arguments.of(FAILED, ProviderOutcome.UNKNOWN, FAILED, false, Reason.NON_AUTHORITATIVE));
    }

    @ParameterizedTest(name = "{0} + {1} -> {2}")
    @MethodSource("matrix")
    void legalAndIgnoredPairs(PaymentStatus from, ProviderOutcome outcome, PaymentStatus expected, boolean applied,
            Reason ignoredReason) {
        Payment p = in(from);
        long versionBefore = p.aggregateVersion();

        TransitionResult r = p.transition(obs(outcome, null));

        assertThat(p.status()).isEqualTo(expected);
        if (applied) {
            assertThat(r).isEqualTo(new Applied(from, expected, versionBefore + 1));
            assertThat(p.aggregateVersion()).isEqualTo(versionBefore + 1);
        } else {
            assertThat(r).isEqualTo(new Ignored(ignoredReason));
            assertThat(p.aggregateVersion()).isEqualTo(versionBefore);
        }
    }

    @Test
    void conflictingTerminalEvidenceLeavesStateUnchanged() {
        Payment succeeded = in(SUCCEEDED);
        assertThat(succeeded.transition(obs(DECLINED, null)))
                .isEqualTo(new ConflictingTerminalEvidence(SUCCEEDED, DECLINED, null));
        assertThat(succeeded.status()).isEqualTo(SUCCEEDED);
        assertThat(succeeded.aggregateVersion()).isEqualTo(3);

        Payment failed = in(FAILED);
        assertThat(failed.transition(obs(ProviderOutcome.SUCCEEDED, 9L)))
                .isEqualTo(new ConflictingTerminalEvidence(FAILED, ProviderOutcome.SUCCEEDED, 9L));
        assertThat(failed.status()).isEqualTo(FAILED);
    }

    @ParameterizedTest
    @EnumSource(ProviderOutcome.class)
    void anyObservationBeforeProcessingIsIllegal(ProviderOutcome outcome) {
        Payment p = in(REQUESTED);
        assertThatThrownBy(() -> p.transition(obs(outcome, null))).isInstanceOf(IllegalTransitionException.class);
        assertThat(p.status()).isEqualTo(REQUESTED);
    }

    @Test
    void startProcessingOnlyFromRequested() {
        Payment p = Payment.request(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                new Money(2500, CurrencyCode.USD));
        assertThat(p.status()).isEqualTo(REQUESTED);
        assertThat(p.aggregateVersion()).isEqualTo(1);

        assertThat(p.startProcessing()).isEqualTo(new Applied(REQUESTED, PROCESSING, 2));

        for (PaymentStatus s : new PaymentStatus[] {PROCESSING, UNKNOWN, SUCCEEDED, FAILED}) {
            Payment other = in(s);
            assertThatThrownBy(other::startProcessing).isInstanceOf(IllegalTransitionException.class);
            assertThat(other.status()).isEqualTo(s);
        }
    }

    @Test
    void oldDeclineAfterSuccessCannotOverwriteSucceeded() {
        Payment p = in(PROCESSING);
        assertThat(p.transition(obs(ProviderOutcome.SUCCEEDED, 5L))).isInstanceOf(Applied.class);

        TransitionResult r = p.transition(obs(DECLINED, 3L));

        assertThat(r).isEqualTo(new Ignored(Reason.OLDER_REVISION));
        assertThat(p.status()).isEqualTo(SUCCEEDED);
        assertThat(p.lastProviderRevision()).isEqualTo(5L);
    }

    @Test
    void olderRevisionNeverOverwritesNewerWhileOpen() {
        Payment p = in(PROCESSING, 7L);
        assertThat(p.transition(obs(ProviderOutcome.UNKNOWN, 6L))).isEqualTo(new Ignored(Reason.OLDER_REVISION));
        assertThat(p.status()).isEqualTo(PROCESSING);
        assertThat(p.transition(obs(ProviderOutcome.SUCCEEDED, 8L))).isInstanceOf(Applied.class);
        assertThat(p.lastProviderRevision()).isEqualTo(8L);
    }

    @Test
    void equalRevisionWithDifferentTerminalIsStillAConflict() {
        Payment p = in(SUCCEEDED, 5L);
        assertThat(p.transition(obs(DECLINED, 5L))).isInstanceOf(ConflictingTerminalEvidence.class);
        assertThat(p.status()).isEqualTo(SUCCEEDED);
        assertThat(p.lastProviderRevision()).isEqualTo(5L);
    }

    @Test
    void pendingObservationAdvancesRevisionButNotStatus() {
        Payment p = in(PROCESSING, 1L);
        assertThat(p.transition(obs(PENDING, 4L))).isEqualTo(new Ignored(Reason.NO_STATE_CHANGE));
        assertThat(p.lastProviderRevision()).isEqualTo(4L);
        assertThat(p.status()).isEqualTo(PROCESSING);
    }

    @Test
    void unknownIsRecoverableToEitherTerminal() {
        Payment toSuccess = in(UNKNOWN);
        assertThat(toSuccess.transition(obs(ProviderOutcome.SUCCEEDED, null))).isInstanceOf(Applied.class);
        assertThat(toSuccess.status()).isEqualTo(SUCCEEDED);

        Payment toFailed = in(UNKNOWN);
        assertThat(toFailed.transition(obs(DECLINED, null))).isInstanceOf(Applied.class);
        assertThat(toFailed.status()).isEqualTo(FAILED);
    }

    @Test
    void terminalStatesNeverRegressForAnyOutcome() {
        for (PaymentStatus terminal : new PaymentStatus[] {SUCCEEDED, FAILED}) {
            for (ProviderOutcome outcome : ProviderOutcome.values()) {
                Payment p = in(terminal);
                p.transition(obs(outcome, null));
                assertThat(p.status()).as("%s + %s", terminal, outcome).isEqualTo(terminal);
            }
        }
    }

    @Test
    void rejectsNullObservationAndNegativeRevision() {
        assertThatThrownBy(() -> in(PROCESSING).transition(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> obs(PENDING, -1L)).isInstanceOf(IllegalArgumentException.class);
    }
}
