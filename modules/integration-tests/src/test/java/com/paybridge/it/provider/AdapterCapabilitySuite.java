package com.paybridge.it.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.paybridge.application.port.BillingProvider;
import com.paybridge.application.port.BillingProvider.ErrorClass;
import com.paybridge.application.port.BillingProvider.ProviderResult;
import com.paybridge.application.port.BillingProvider.ProviderResult.Kind;
import com.paybridge.domain.payment.ProviderOutcome;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * The correctness checks every payment method must pass, written against the {@link BillingProvider} port only
 * (FR-008, FR-031). A subclass supplies the method token; the same suite then runs for SIMULATED_CARD (immediate) and
 * SIMULATED_TRANSFER (delayed). A real provider adapter would be accepted only by passing this suite plus its own
 * documented contract tests.
 */
abstract class AdapterCapabilitySuite extends SimulatorIT {
    /** The synthetic method token under test. */
    abstract String methodToken();

    private ProviderResult chargeOnce(String pay, long amount) {
        return provider().charge(charge(pay, amount, methodToken(), "default"));
    }

    /** Immediate methods answer SUCCEEDED; delayed ones answer PENDING and reach SUCCEEDED later. */
    private ProviderResult settle(String pay, long amount) {
        await("final outcome", Duration.ofSeconds(20), () -> {
            var r = lookupResult(pay, amount);
            return r.isObserved() && r.observation().outcome() != ProviderOutcome.PENDING;
        });
        return lookupResult(pay, amount);
    }

    @Test
    void aChargeIsAcceptedAndEventuallySucceeds() {
        String pay = newPaymentId();
        var r = chargeOnce(pay, 150_000);
        assertThat(r.kind()).isEqualTo(Kind.OBSERVED);
        assertThat(r.observation().outcome()).isIn(ProviderOutcome.SUCCEEDED, ProviderOutcome.PENDING);
        assertThat(r.observation().providerReference()).startsWith("sim_ch_");
        assertThat(settle(pay, 150_000).observation().outcome()).isEqualTo(ProviderOutcome.SUCCEEDED);
    }

    @Test
    void theSameKeyIsOneOperationWithOneReference() {
        String pay = newPaymentId();
        var first = chargeOnce(pay, 20_000);
        var second = chargeOnce(pay, 20_000);
        assertThat(second.observation().providerReference()).isEqualTo(first.observation().providerReference());
        assertThat(charges(pay)).isOne();
    }

    @Test
    void aChangedAmountForTheSameKeyConflicts() {
        String pay = newPaymentId();
        chargeOnce(pay, 20_000);
        var conflict = chargeOnce(pay, 20_001);
        assertThat(conflict.kind()).isEqualTo(Kind.IDEMPOTENCY_CONFLICT);
        assertThat(conflict.observation()).isNull();
    }

    @Test
    void revisionsOnlyGoUp() {
        String pay = newPaymentId();
        long previous = chargeOnce(pay, 30_000).observation().providerRevision();
        var settled = settle(pay, 30_000);
        assertThat(settled.observation().providerRevision()).isGreaterThanOrEqualTo(previous);
        assertThat(lookupResult(pay, 30_000).observation().providerRevision()).isGreaterThanOrEqualTo(settled.observation().providerRevision());
    }

    @Test
    void anUnknownKeyIsNotFoundAndNotADecline() {
        var r = lookupResult(newPaymentId(), 1_000);
        assertThat(r.kind()).isEqualTo(Kind.NOT_FOUND);
        assertThat(r.observation()).isNull();
    }

    @Test
    void anUnavailableScenarioRunIsAClientErrorClassNeverADecline() {
        var r = provider().charge(charge(newPaymentId(), 1_000, methodToken(), "no-such-run"));
        assertThat(r.kind()).isEqualTo(Kind.SCENARIO_NOT_AVAILABLE);
        assertThat(r.observation()).isNull();
    }

    @Test
    void aLookupAboutAnotherAmountIsRejectedAsMalformed() {
        String pay = newPaymentId();
        chargeOnce(pay, 40_000);
        assertThat(lookupResult(pay, 40_001).errorClass()).isEqualTo(ErrorClass.MALFORMED);
    }

    @Test
    void theAdapterNeverThrowsForATransportFailure() {
        var dead = new com.paybridge.adapter.provider.SimulatorBillingProvider(java.net.URI.create("http://127.0.0.1:1"), "t",
                Duration.ofMillis(300), Duration.ofSeconds(1));
        var r = dead.charge(charge(newPaymentId(), 1_000, methodToken(), "default"));
        assertThat(r.errorClass()).isEqualTo(ErrorClass.RETRYABLE_UNAVAILABLE);
        assertThat(dead.lookup(lookup(newPaymentId(), 1_000)).errorClass()).isEqualTo(ErrorClass.RETRYABLE_UNAVAILABLE);
    }

    @Test
    void theProviderKeyIsPbkPlusThePaymentId() {
        String pay = newPaymentId();
        assertThat(BillingProvider.providerKey(pay)).isEqualTo("pbk-" + pay);
        chargeOnce(pay, 2_000);
        assertThat(simJdbc.queryForObject("SELECT idempotency_key FROM sim_charge WHERE payment_id = ?", String.class, pay))
                .isEqualTo("pbk-" + pay);
    }
}
