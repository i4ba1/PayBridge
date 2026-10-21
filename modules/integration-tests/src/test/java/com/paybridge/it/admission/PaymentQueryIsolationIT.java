package com.paybridge.it.admission;

import static org.assertj.core.api.Assertions.assertThat;

import com.paybridge.application.port.IdentityStore.GlobalRole;
import com.paybridge.it.support.CoreHttpIT;
import com.paybridge.it.support.Fixtures;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PaymentQueryIsolationIT extends CoreHttpIT {
    static final String QUERY = "query PaymentById($id: ID!) { payment(id: $id) { id status version amountMinor } }";

    @Test
    void anotherMerchantsPaymentIsNullAndIndistinguishableFromAMissingOne() {
        String a = merchant("acme", "IDR");
        String b = merchant("rival", "IDR");
        String operatorA = user("op-a", GlobalRole.USER, false);
        member(operatorA, a, "OPERATOR");
        String viewerB = user("view-b", GlobalRole.USER, false);
        member(viewerB, b, "VIEWER");
        String invoice = Fixtures.invoice(rootJdbc, a, 150_000, "IDR");
        Fixtures.method(rootJdbc, a, "SIMULATED_CARD", "tok_sim_card_default");

        var admitted = call(operatorA, a, "RequestPayment", RequestPaymentHttpIT.REQUEST, Map.of("input",
                Map.of("invoiceId", invoice, "methodToken", "tok_sim_card_default", "idempotencyKey", Fixtures.uuid())));
        String paymentId = admitted.data().path("requestPayment").path("payment").path("id").asText();
        assertThat(paymentId).isNotBlank();

        var foreign = call(viewerB, b, "PaymentById", QUERY, Map.of("id", paymentId));
        var missing = call(viewerB, b, "PaymentById", QUERY, Map.of("id", Fixtures.uuid()));
        assertThat(foreign.body().has("errors")).isFalse();
        assertThat(foreign.data().path("payment").isNull()).isTrue();
        assertThat(foreign.body()).isEqualTo(missing.body());

        var own = call(operatorA, a, "PaymentById", QUERY, Map.of("id", paymentId));
        assertThat(own.data().path("payment").path("status").asText()).isEqualTo("REQUESTED");
        assertThat(own.data().path("payment").path("version").asText()).isEqualTo("1");
        assertThat(own.data().path("payment").path("amountMinor").asText()).isEqualTo("150000");
    }
}
