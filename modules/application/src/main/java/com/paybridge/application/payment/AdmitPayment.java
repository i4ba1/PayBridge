package com.paybridge.application.payment;

import com.paybridge.application.payment.PaymentAdmission.Accepted;
import com.paybridge.application.payment.PaymentAdmission.Code;
import com.paybridge.application.payment.PaymentAdmission.FieldProblem;
import com.paybridge.application.payment.PaymentAdmission.Rejected;
import com.paybridge.application.payment.RequestPaymentCommand.Parse;
import com.paybridge.application.port.AdmissionGate;
import com.paybridge.application.port.AdmissionStore;
import com.paybridge.application.port.AdmissionStore.AdmissionRequest;
import com.paybridge.application.port.AdmissionStore.AdmissionResult;
import com.paybridge.application.port.AdmissionStore.Replay;
import com.paybridge.application.port.ScenarioRunResolver;
import com.paybridge.application.port.SystemFlags;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongConsumer;
import java.util.function.Supplier;

/**
 * Payment admission (DMR 6.1, FR-007 to FR-015). Order: validate, replay lookup, gates, method, one writer transaction.
 * The provider is never called here. A replay is answered before any gate, so a retry of an accepted request still gets
 * its answer while admission is closed.
 */
public final class AdmitPayment {

    public record Settings(boolean enabled, int methodTokenMaxLength, int lockWaitTimeoutSeconds, int maxAttempts,
                           long jitterMaxMillis, int retryAfterSeconds) {}

    private final AdmissionStore store;
    private final AdmissionGate gate;
    private final SystemFlags flags;
    private final ScenarioRunResolver runs;
    private final Settings settings;
    private final LongConsumer sleeper;
    private final Supplier<String> ids;

    public AdmitPayment(AdmissionStore store, AdmissionGate gate, SystemFlags flags, ScenarioRunResolver runs,
                        Settings settings, LongConsumer sleeper, Supplier<String> ids) {
        this.store = store;
        this.gate = gate;
        this.flags = flags;
        this.runs = runs;
        this.settings = settings;
        this.sleeper = sleeper;
        this.ids = ids;
    }

    public PaymentAdmission admit(AdmissionActor actor, String invoiceId, String methodToken, String idempotencyKey) {
        Parse parsed = RequestPaymentCommand.parse(invoiceId, methodToken, idempotencyKey, settings.methodTokenMaxLength());
        if (parsed instanceof Parse.Invalid invalid) {
            return new Rejected(Code.VALIDATION_ERROR, null, invalid.problems());
        }
        RequestPaymentCommand command = ((Parse.Valid) parsed).command();
        String hash = command.requestHash();

        Optional<Replay> replay = store.findReplay(actor.merchantId(), command.idempotencyKey());
        if (replay.isPresent()) {
            return answerFor(replay.get(), hash);
        }

        Rejected closed = closedGate(actor.merchantId());
        if (closed != null) {
            return closed;
        }

        var method = store.resolveMethod(actor.merchantId(), command.methodToken());
        if (method.isEmpty()) {
            return new Rejected(Code.VALIDATION_ERROR, null,
                    List.of(new FieldProblem("methodToken", "INVALID", "Is not an accepted token.")));
        }

        String paymentId = ids.get();
        var request = new AdmissionRequest(paymentId, ids.get(), actor.merchantId(), actor.userId(), command.invoiceId(),
                command.idempotencyKey(), hash, method.get().methodCode(), command.methodToken(),
                runs.runFor(actor.merchantId()), actor.correlationId(), settings.lockWaitTimeoutSeconds());

        for (int attempt = 1; attempt <= settings.maxAttempts(); attempt++) {
            PaymentAdmission answer = switch (store.admit(request)) {
                case AdmissionResult.Created _ -> new Accepted(paymentId, "REQUESTED", 1, false);
                case AdmissionResult.Duplicate duplicate -> answerFor(duplicate.existing(), hash);
                case AdmissionResult.InvoiceNotFound _ -> Rejected.of(Code.NOT_FOUND);
                case AdmissionResult.NotEligible _ -> Rejected.of(Code.INVOICE_NOT_ELIGIBLE);
                case AdmissionResult.Transient _ -> null;   // retry below, then TEMPORARILY_UNAVAILABLE
            };
            if (answer != null) {
                return answer;
            }
            if (attempt < settings.maxAttempts()) {
                sleeper.accept(ThreadLocalRandom.current().nextLong(1, settings.jitterMaxMillis() + 1));
            }
        }
        return Rejected.of(Code.TEMPORARILY_UNAVAILABLE, settings.retryAfterSeconds());
    }

    private PaymentAdmission answerFor(Replay replay, String hash) {
        if (replay.requestHash().equals(hash)) {
            return new Accepted(replay.paymentId(), replay.status(), replay.aggregateVersion(), true);
        }
        return Rejected.of(Code.IDEMPOTENCY_CONFLICT);
    }

    /** Static switch, then maintenance flag, then capacity flag, then the quota gate. Returns null when all are open. */
    private Rejected closedGate(String merchantId) {
        if (!settings.enabled() || flags.maintenanceOn() || flags.capacityClosed()) {
            return Rejected.of(Code.TEMPORARILY_UNAVAILABLE, settings.retryAfterSeconds());
        }
        return gate.check(merchantId).map(c -> Rejected.of(c.code(), c.retryAfterSeconds())).orElse(null);
    }
}
