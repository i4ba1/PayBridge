package com.paybridge.core.graphql;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsMutation;
import com.netflix.graphql.dgs.DgsQuery;
import com.netflix.graphql.dgs.InputArgument;
import com.paybridge.application.payment.AdmissionActor;
import com.paybridge.application.payment.AdmitPayment;
import com.paybridge.application.payment.PaymentAdmission;
import com.paybridge.application.port.PaymentStore;
import com.paybridge.application.port.PaymentStore.PaymentRecord;
import com.paybridge.core.graphql.model.IdentityTypes.FieldProblem;
import com.paybridge.core.graphql.model.IdentityTypes.OperationProblem;
import com.paybridge.core.graphql.model.PaymentTypes.Payment;
import com.paybridge.core.graphql.model.PaymentTypes.PaymentAccepted;
import com.paybridge.core.graphql.model.PaymentTypes.RequestPaymentInput;
import com.paybridge.core.identity.ActorContext;
import graphql.schema.DataFetchingEnvironment;
import java.util.List;
import java.util.Map;

/**
 * requestPayment and payment(id). Authorization (OPERATOR / VIEWER in the signed merchant) has already happened in
 * {@link AuthorizationInstrumentation}. A payment of another merchant is reported exactly like a missing one.
 */
@DgsComponent
public class PaymentDataFetcher {
    private static final Map<PaymentAdmission.Code, String> MESSAGES = Map.of(
            PaymentAdmission.Code.VALIDATION_ERROR, "The request is not valid.",
            PaymentAdmission.Code.NOT_FOUND, "Invoice not found.",
            PaymentAdmission.Code.IDEMPOTENCY_CONFLICT, "This idempotency key was already used for a different request.",
            PaymentAdmission.Code.INVOICE_NOT_ELIGIBLE, "The invoice already has an active payment.",
            PaymentAdmission.Code.RATE_LIMITED, "Too many requests. Try again later.",
            PaymentAdmission.Code.TEMPORARILY_UNAVAILABLE,
            "Payments are temporarily unavailable. Retry with the same idempotency key.");

    private final AdmitPayment admitPayment;
    private final PaymentStore payments;

    public PaymentDataFetcher(AdmitPayment admitPayment, PaymentStore payments) {
        this.admitPayment = admitPayment;
        this.payments = payments;
    }

    @DgsMutation
    public Object requestPayment(@InputArgument RequestPaymentInput input, DataFetchingEnvironment env) {
        ActorContext actor = IdentityDataFetcher.granted(env).actor();
        PaymentAdmission result = admitPayment.admit(new AdmissionActor(actor.merchantId(), actor.userId(),
                actor.correlationId()), input.invoiceId(), input.methodToken(), input.idempotencyKey());
        if (result instanceof PaymentAdmission.Accepted accepted) {
            PaymentRecord record = payments.findById(accepted.paymentId())
                    .orElseThrow(() -> new IllegalStateException("admitted payment is not readable"));
            return new PaymentAccepted(toPayment(record), accepted.replayed());
        }
        PaymentAdmission.Rejected rejected = (PaymentAdmission.Rejected) result;
        List<FieldProblem> fields = rejected.fieldProblems() == null ? null
                : rejected.fieldProblems().stream().map(f -> new FieldProblem(f.field(), f.code(), f.message())).toList();
        return new OperationProblem(rejected.code().name(), MESSAGES.get(rejected.code()), rejected.retryAfterSeconds(),
                fields, null);
    }

    @DgsQuery
    public Payment payment(@InputArgument String id, DataFetchingEnvironment env) {
        String merchantId = IdentityDataFetcher.granted(env).actor().merchantId();
        return payments.findById(id).filter(p -> p.merchantId().equals(merchantId)).map(PaymentDataFetcher::toPayment)
                .orElse(null);
    }

    private static Payment toPayment(PaymentRecord p) {
        return new Payment(p.id(), p.merchantId(), p.invoiceId(), p.status(), Long.toString(p.amountMinor()), p.currency(),
                p.methodCode(), Long.toString(p.aggregateVersion()), p.providerReference(), p.createdAt(), p.updatedAt(),
                List.of(), null);
    }
}
