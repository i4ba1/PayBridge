package com.paybridge.it.admission;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.paybridge.application.port.IdentityStore.GlobalRole;
import com.paybridge.it.support.CoreHttpIT;
import com.paybridge.it.support.Fixtures;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RequestPaymentHttpIT extends CoreHttpIT {
    static final String TOKEN = "tok_sim_card_default";
    static final String REQUEST = "mutation RequestPayment($input: RequestPaymentInput!) { requestPayment(input: $input) { "
            + "__typename ... on PaymentAccepted { replayed payment { id merchantId invoiceId status amountMinor currency "
            + "methodCode version providerReference attempts { attemptNumber } uncertainty { message } } } " + PROBLEM + " } }";

    String merchant;
    String operator;
    String viewer;
    String invoice;

    @BeforeEach
    void world() {
        merchant = merchant("acme", "IDR");
        operator = user("operator", GlobalRole.USER, false);
        member(operator, merchant, "OPERATOR");
        viewer = user("viewer", GlobalRole.USER, false);
        member(viewer, merchant, "VIEWER");
        invoice = Fixtures.invoice(rootJdbc, merchant, 150_000, "IDR");
        Fixtures.method(rootJdbc, merchant, "SIMULATED_CARD", TOKEN);
    }

    Response request(String user, String invoiceId, String token, String key) {
        return call(user, merchant, "RequestPayment", REQUEST,
                Map.of("input", Map.of("invoiceId", invoiceId, "methodToken", token, "idempotencyKey", key)));
    }

    JsonNode result(Response r) {
        assertThat(r.body().has("errors")).as(r.body().toString()).isFalse();
        return r.data().path("requestPayment");
    }

    @Test
    void anOperatorAdmitsAPaymentAndGetsTheAsyncAcknowledgement() {
        var response = request(operator, invoice, TOKEN, Fixtures.uuid());
        var payload = result(response);

        assertThat(payload.path("__typename").asText()).isEqualTo("PaymentAccepted");
        assertThat(payload.path("replayed").asBoolean()).isFalse();
        var payment = payload.path("payment");
        assertThat(payment.path("status").asText()).isEqualTo("REQUESTED");
        assertThat(payment.path("version").asText()).isEqualTo("1");
        assertThat(payment.path("amountMinor").asText()).isEqualTo("150000");
        assertThat(payment.path("currency").asText()).isEqualTo("IDR");
        assertThat(payment.path("methodCode").asText()).isEqualTo("SIMULATED_CARD");
        assertThat(payment.path("invoiceId").asText()).isEqualTo(invoice);
        assertThat(payment.path("merchantId").asText()).isEqualTo(merchant);
        assertThat(payment.path("providerReference").isNull()).isTrue();
        assertThat(payment.path("attempts")).isEmpty();
        assertThat(payment.path("uncertainty").isNull()).isTrue();

        assertThat(auditCount("PAYMENT_ADMITTED")).isOne();
        assertThat(rootJdbc.queryForObject("SELECT COUNT(*) FROM outbox WHERE status = 'PENDING'", Long.class)).isEqualTo(2);
        assertThat(rootJdbc.queryForObject("SELECT COUNT(*) FROM payment_attempt", Long.class)).isZero();
    }

    @Test
    void anIdenticalRepeatReturnsTheSamePaymentAsAReplay() {
        String key = Fixtures.uuid();
        var first = result(request(operator, invoice, TOKEN, key)).path("payment").path("id").asText();
        var again = result(request(operator, invoice, TOKEN, key));

        assertThat(again.path("replayed").asBoolean()).isTrue();
        assertThat(again.path("payment").path("id").asText()).isEqualTo(first);
        assertThat(rootJdbc.queryForObject("SELECT COUNT(*) FROM payment", Long.class)).isOne();
        assertThat(auditCount("PAYMENT_ADMITTED")).isOne();
    }

    @Test
    void aSecondKeyOnTheSameInvoiceIsNotEligible() {
        result(request(operator, invoice, TOKEN, Fixtures.uuid()));
        var second = result(request(operator, invoice, TOKEN, Fixtures.uuid()));
        assertThat(second.path("__typename").asText()).isEqualTo("OperationProblem");
        assertThat(second.path("code").asText()).isEqualTo("INVOICE_NOT_ELIGIBLE");
        assertThat(rootJdbc.queryForObject("SELECT COUNT(*) FROM payment", Long.class)).isOne();
    }

    @Test
    void aChangedPayloadForTheSameKeyIsAnIdempotencyConflict() {
        String key = Fixtures.uuid();
        result(request(operator, invoice, TOKEN, key));
        Fixtures.method(rootJdbc, merchant, "SIMULATED_TRANSFER", "tok_sim_transfer_default");
        var conflict = result(request(operator, invoice, "tok_sim_transfer_default", key));
        assertThat(conflict.path("code").asText()).isEqualTo("IDEMPOTENCY_CONFLICT");
        assertThat(conflict.path("payment").isMissingNode()).isTrue();
    }

    @Test
    void aViewerCannotRequestAPayment() {
        var problem = result(request(viewer, invoice, TOKEN, Fixtures.uuid()));
        assertThat(problem.path("code").asText()).isEqualTo("FORBIDDEN");
        assertThat(rootJdbc.queryForObject("SELECT COUNT(*) FROM payment_request", Long.class)).isZero();
    }

    @Test
    void aForeignInvoiceAndAMissingInvoiceGiveTheSameAnswer() {
        String other = merchant("rival", "IDR");
        String foreign = Fixtures.invoice(rootJdbc, other, 1_000, "IDR");
        var a = result(request(operator, foreign, TOKEN, Fixtures.uuid()));
        var b = result(request(operator, Fixtures.uuid(), TOKEN, Fixtures.uuid()));
        assertThat(a.path("code").asText()).isEqualTo("NOT_FOUND");
        assertThat(a).isEqualTo(b);
        assertThat(rootJdbc.queryForObject("SELECT active_payment_id FROM invoice WHERE id = ?", String.class, foreign)).isNull();
    }

    @Test
    void invalidInputIsAValidationErrorNamingTheFields() {
        var problem = result(request(operator, "nope", "has space", "also-bad"));
        assertThat(problem.path("code").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(problem.path("fieldProblems")).extracting(n -> n.path("field").asText())
                .containsExactly("invoiceId", "methodToken", "idempotencyKey");
        assertThat(problem.toString()).doesNotContain("also-bad").doesNotContain("has space");
        assertThat(rootJdbc.queryForObject("SELECT COUNT(*) FROM payment_request", Long.class)).isZero();
    }
}
