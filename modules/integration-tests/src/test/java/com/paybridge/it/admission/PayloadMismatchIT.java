package com.paybridge.it.admission;

import static org.assertj.core.api.Assertions.assertThat;

import com.paybridge.application.payment.PaymentAdmission.Accepted;
import com.paybridge.application.payment.PaymentAdmission.Code;
import com.paybridge.application.payment.PaymentAdmission.Rejected;
import com.paybridge.it.support.Fixtures;
import org.junit.jupiter.api.Test;

class PayloadMismatchIT extends AdmissionIT {

    @Test
    void sameKeyWithAnotherMethodTokenIsAConflictAndCreatesNothing() {
        Fixtures.method(rootJdbc, merchant, "SIMULATED_TRANSFER", "tok_sim_transfer_default");
        String key = key();
        var first = (Accepted) request(key);

        var result = admit.admit(actor(), invoice, "tok_sim_transfer_default", key);

        assertThat(result).isEqualTo(Rejected.of(Code.IDEMPOTENCY_CONFLICT));
        assertThat(count("payment")).isOne();
        assertThat(rootJdbc.queryForObject("SELECT method_code FROM payment WHERE id = ?", String.class, first.paymentId()))
                .isEqualTo(METHOD);
    }

    @Test
    void sameKeyForAnotherInvoiceIsAConflictAndTheOtherInvoiceStaysFree() {
        String key = key();
        request(key);
        String second = Fixtures.invoice(rootJdbc, merchant, 10_000, "IDR");

        var result = admit.admit(actor(), second, TOKEN, key);

        assertThat(result).isEqualTo(Rejected.of(Code.IDEMPOTENCY_CONFLICT));
        assertThat(rootJdbc.queryForObject("SELECT active_payment_id FROM invoice WHERE id = ?", String.class, second)).isNull();
    }
}
