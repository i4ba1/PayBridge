package com.paybridge.application.payment;

import static com.paybridge.application.payment.PaymentAdmission.Code.IDEMPOTENCY_CONFLICT;
import static com.paybridge.application.payment.PaymentAdmission.Code.INVOICE_NOT_ELIGIBLE;
import static com.paybridge.application.payment.PaymentAdmission.Code.NOT_FOUND;
import static com.paybridge.application.payment.PaymentAdmission.Code.RATE_LIMITED;
import static com.paybridge.application.payment.PaymentAdmission.Code.TEMPORARILY_UNAVAILABLE;
import static com.paybridge.application.payment.PaymentAdmission.Code.VALIDATION_ERROR;
import static org.assertj.core.api.Assertions.assertThat;

import com.paybridge.application.payment.PaymentAdmission.Accepted;
import com.paybridge.application.payment.PaymentAdmission.Rejected;
import com.paybridge.application.port.AdmissionGate;
import com.paybridge.application.port.AdmissionStore;
import com.paybridge.application.port.AdmissionStore.AdmissionRequest;
import com.paybridge.application.port.AdmissionStore.AdmissionResult;
import com.paybridge.application.port.AdmissionStore.MethodChoice;
import com.paybridge.application.port.AdmissionStore.Replay;
import com.paybridge.application.port.SystemFlags;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AdmitPaymentTest {
    private static final String MERCHANT = "00000000-0000-4000-8000-000000000301";
    private static final String USER = "00000000-0000-4000-8000-000000000501";
    private static final String INV = "00000000-0000-4000-8000-000000000401";
    private static final String KEY = "7d5c1e0a-3b7e-4f0e-9a55-0d6f3b9c2a11";
    private static final String TOKEN = "tok_sim_card_default";
    private static final String HASH = RequestPaymentCommand.parse(INV, TOKEN, KEY, 128) instanceof RequestPaymentCommand.Parse.Valid v
            ? v.command().requestHash() : null;
    private static final AdmissionActor ACTOR = new AdmissionActor(MERCHANT, USER, "corr-1");

    static class FakeStore implements AdmissionStore {
        final List<String> calls = new ArrayList<>();
        final List<AdmissionRequest> requests = new ArrayList<>();
        Optional<Replay> replay = Optional.empty();
        Optional<MethodChoice> method = Optional.of(new MethodChoice("SIMULATED_CARD"));
        final Deque<AdmissionResult> results = new ArrayDeque<>();

        @Override public Optional<Replay> findReplay(String merchantId, String key) { calls.add("findReplay"); return replay; }
        @Override public Optional<MethodChoice> resolveMethod(String merchantId, String token) { calls.add("resolveMethod"); return method; }
        @Override public AdmissionResult admit(AdmissionRequest request) {
            calls.add("admit");
            requests.add(request);
            return results.isEmpty() ? new AdmissionResult.Created(request.paymentId()) : results.poll();
        }
    }

    static class FakeFlags implements SystemFlags {
        final List<String> reads = new ArrayList<>();
        boolean maintenance;
        boolean capacityClosed;
        @Override public boolean maintenanceOn() { reads.add("maintenance"); return maintenance; }
        @Override public boolean capacityClosed() { reads.add("capacity"); return capacityClosed; }
    }

    private final FakeStore store = new FakeStore();
    private final FakeFlags flags = new FakeFlags();
    private final List<String> gateCalls = new ArrayList<>();
    private Optional<AdmissionGate.Closed> gateAnswer = Optional.empty();
    private final List<Long> sleeps = new ArrayList<>();
    private final AtomicInteger idCounter = new AtomicInteger();

    private AdmitPayment useCase(boolean enabled) {
        return new AdmitPayment(store, m -> { gateCalls.add(m); return gateAnswer; }, flags, m -> "run-7",
                new AdmitPayment.Settings(enabled, 128, 3, 3, 5, 5), sleeps::add,
                () -> "00000000-0000-4000-8000-0000000009" + String.format("%02d", idCounter.incrementAndGet()));
    }

    private PaymentAdmission admit(AdmitPayment useCase) {
        return useCase.admit(ACTOR, INV, TOKEN, KEY);
    }

    @Test
    void invalidInputIsRejectedBeforeAnyStoreOrGateCall() {
        var result = useCase(true).admit(ACTOR, "nope", TOKEN, KEY);
        assertThat(result).isInstanceOf(Rejected.class);
        var rejected = (Rejected) result;
        assertThat(rejected.code()).isEqualTo(VALIDATION_ERROR);
        assertThat(rejected.fieldProblems()).extracting(PaymentAdmission.FieldProblem::field).containsExactly("invoiceId");
        assertThat(store.calls).isEmpty();
        assertThat(gateCalls).isEmpty();
        assertThat(flags.reads).isEmpty();
    }

    @Test
    void anIdenticalRepeatIsServedEvenWhenEveryGateIsClosed() {
        store.replay = Optional.of(new Replay("p-1", HASH, "PROCESSING", 2));
        flags.maintenance = true;
        flags.capacityClosed = true;
        gateAnswer = Optional.of(new AdmissionGate.Closed(RATE_LIMITED, 9));

        var result = admit(useCase(false));

        assertThat(result).isEqualTo(new Accepted("p-1", "PROCESSING", 2, true));
        assertThat(store.calls).containsExactly("findReplay");
        assertThat(flags.reads).isEmpty();
        assertThat(gateCalls).isEmpty();
    }

    @Test
    void aRepeatWithADifferentPayloadIsAConflictAndNeverNamesThePayment() {
        store.replay = Optional.of(new Replay("p-1", "0".repeat(64), "REQUESTED", 1));
        var result = admit(useCase(true));
        assertThat(result).isEqualTo(Rejected.of(IDEMPOTENCY_CONFLICT));
        assertThat(store.calls).containsExactly("findReplay");
    }

    @Test
    void gatesAreEvaluatedInOrderAndStopAtTheFirstClosedOne() {
        // 1. static switch off: no flag and no gate is read
        assertThat(admit(useCase(false))).isEqualTo(Rejected.of(TEMPORARILY_UNAVAILABLE, 5));
        assertThat(flags.reads).isEmpty();
        assertThat(gateCalls).isEmpty();

        // 2. maintenance on: capacity and the gate are not read
        flags.maintenance = true;
        assertThat(admit(useCase(true))).isEqualTo(Rejected.of(TEMPORARILY_UNAVAILABLE, 5));
        assertThat(flags.reads).containsExactly("maintenance");
        assertThat(gateCalls).isEmpty();

        // 3. capacity closed: the gate is not read
        flags.reads.clear();
        flags.maintenance = false;
        flags.capacityClosed = true;
        assertThat(admit(useCase(true))).isEqualTo(Rejected.of(TEMPORARILY_UNAVAILABLE, 5));
        assertThat(flags.reads).containsExactly("maintenance", "capacity");
        assertThat(gateCalls).isEmpty();

        // 4. quota gate closed: its code and retry-after are returned
        flags.capacityClosed = false;
        gateAnswer = Optional.of(new AdmissionGate.Closed(RATE_LIMITED, 9));
        assertThat(admit(useCase(true))).isEqualTo(Rejected.of(RATE_LIMITED, 9));
        assertThat(gateCalls).containsExactly(MERCHANT);

        assertThat(store.calls).doesNotContain("admit", "resolveMethod");
    }

    @Test
    void anUnknownOrDisabledMethodTokenIsAValidationError() {
        store.method = Optional.empty();
        var rejected = (Rejected) admit(useCase(true));
        assertThat(rejected.code()).isEqualTo(VALIDATION_ERROR);
        assertThat(rejected.fieldProblems()).extracting(PaymentAdmission.FieldProblem::field).containsExactly("methodToken");
        assertThat(store.calls).doesNotContain("admit");
    }

    @Test
    void aNewRequestIsAdmittedWithTheResolvedValuesAndAcknowledgedAsRequested() {
        var result = admit(useCase(true));

        assertThat(result).isEqualTo(new Accepted("00000000-0000-4000-8000-000000000901", "REQUESTED", 1, false));
        assertThat(store.requests).hasSize(1);
        var request = store.requests.get(0);
        assertThat(request).isEqualTo(new AdmissionRequest("00000000-0000-4000-8000-000000000901",
                "00000000-0000-4000-8000-000000000902", MERCHANT, USER, INV, KEY, HASH, "SIMULATED_CARD", TOKEN, "run-7",
                "corr-1", 3));
        assertThat(store.calls).containsExactly("findReplay", "resolveMethod", "admit");
        assertThat(sleeps).isEmpty();
    }

    @Test
    void aLostKeyRaceReturnsTheWinnersStateOrAConflict() {
        store.results.add(new AdmissionResult.Duplicate(new Replay("p-9", HASH, "REQUESTED", 1)));
        assertThat(admit(useCase(true))).isEqualTo(new Accepted("p-9", "REQUESTED", 1, true));

        store.results.add(new AdmissionResult.Duplicate(new Replay("p-9", "f".repeat(64), "REQUESTED", 1)));
        assertThat(admit(useCase(true))).isEqualTo(Rejected.of(IDEMPOTENCY_CONFLICT));
    }

    @Test
    void anUnknownOrIneligibleInvoiceIsRejectedWithoutRetry() {
        store.results.add(new AdmissionResult.InvoiceNotFound());
        assertThat(admit(useCase(true))).isEqualTo(Rejected.of(NOT_FOUND));
        store.results.add(new AdmissionResult.NotEligible());
        assertThat(admit(useCase(true))).isEqualTo(Rejected.of(INVOICE_NOT_ELIGIBLE));
        assertThat(sleeps).isEmpty();
    }

    @Test
    void transientFailuresAreRetriedTwiceWithBoundedJitterThenGiveUp() {
        store.results.add(new AdmissionResult.Transient());
        store.results.add(new AdmissionResult.Transient());
        store.results.add(new AdmissionResult.Transient());

        assertThat(admit(useCase(true))).isEqualTo(Rejected.of(TEMPORARILY_UNAVAILABLE, 5));

        assertThat(store.requests).hasSize(3);
        assertThat(sleeps).hasSize(2).allSatisfy(ms -> assertThat(ms).isBetween(1L, 5L));
        // every attempt reuses the same ids: nothing from a failed attempt was written
        assertThat(store.requests).extracting(AdmissionRequest::paymentId).containsOnly("00000000-0000-4000-8000-000000000901");
    }

    @Test
    void aTransientFailureFollowedBySuccessIsAccepted() {
        store.results.add(new AdmissionResult.Transient());
        var result = admit(useCase(true));
        assertThat(result).isInstanceOf(Accepted.class);
        assertThat(((Accepted) result).replayed()).isFalse();
        assertThat(store.requests).hasSize(2);
        assertThat(sleeps).hasSize(1);
    }
}
