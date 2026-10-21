package com.paybridge.it.admission;

import static org.assertj.core.api.Assertions.assertThat;

import com.paybridge.application.payment.PaymentAdmission.Accepted;
import com.paybridge.application.payment.PaymentAdmission.Rejected;
import com.paybridge.it.support.Fixtures;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AdmissionReplayIT extends AdmissionIT {

    @Test
    void anIdenticalRepeatReturnsTheSamePaymentAndItsCurrentState() {
        String key = key();
        var first = (Accepted) request(key);
        assertThat(first.replayed()).isFalse();
        assertThat(first.status()).isEqualTo("REQUESTED");
        assertThat(first.aggregateVersion()).isEqualTo(1);

        rootJdbc.update("UPDATE payment SET status = 'PROCESSING', aggregate_version = 2 WHERE id = ?", first.paymentId());
        var second = (Accepted) request(key);

        assertThat(second.paymentId()).isEqualTo(first.paymentId());
        assertThat(second.replayed()).isTrue();
        assertThat(second.status()).isEqualTo("PROCESSING");
        assertThat(second.aggregateVersion()).isEqualTo(2);
        assertThat(count("payment")).isOne();
        assertThat(count("payment_request")).isOne();
        assertThat(outboxCount()).isEqualTo(2);
        assertThat(admittedAuditCount()).isOne();
    }

    @Test
    void aReplayIsServedWhileEveryGateIsClosed() {
        String key = key();
        var first = (Accepted) request(key);

        rootJdbc.update("UPDATE system_flag SET flag_value = 'ON' WHERE flag_key = 'admission.maintenance'");
        rootJdbc.update("UPDATE system_flag SET flag_value = 'CLOSED' WHERE flag_key = 'admission.capacity'");
        gateClosed = Optional.of(new com.paybridge.application.port.AdmissionGate.Closed(
                com.paybridge.application.payment.PaymentAdmission.Code.RATE_LIMITED, 9));
        var disabled = useCase(settings(false, 3, 3));

        var again = (Accepted) disabled.admit(actor(), invoice, TOKEN, key);
        assertThat(again.paymentId()).isEqualTo(first.paymentId());
        assertThat(again.replayed()).isTrue();
    }

    @Test
    void uppercaseKeyReplaysTheLowercaseRequest() {
        String key = key();
        var first = (Accepted) request(key);
        var again = (Accepted) request(key.toUpperCase());
        assertThat(again.paymentId()).isEqualTo(first.paymentId());
        assertThat(again.replayed()).isTrue();
        assertThat(count("payment_request")).isOne();
    }

    @Test
    void sameKeyInAnotherMerchantIsANewRequest() {
        String key = key();
        var mine = (Accepted) request(key);

        String otherMerchant = Fixtures.merchant(rootJdbc, "IDR");
        String otherUser = Fixtures.user(rootJdbc);
        String otherInvoice = Fixtures.invoice(rootJdbc, otherMerchant, 99_000, "IDR");
        Fixtures.method(rootJdbc, otherMerchant, METHOD, TOKEN);
        var theirs = admit.admit(new com.paybridge.application.payment.AdmissionActor(otherMerchant, otherUser, "c2"),
                otherInvoice, TOKEN, key);

        assertThat(theirs).isInstanceOf(Accepted.class);
        assertThat(((Accepted) theirs).replayed()).isFalse();
        assertThat(((Accepted) theirs).paymentId()).isNotEqualTo(mine.paymentId());
    }

    @Test
    void aRejectionLeavesNothingBehindAndTheKeyStaysUsable() {
        String key = key();
        gateClosed = Optional.of(new com.paybridge.application.port.AdmissionGate.Closed(
                com.paybridge.application.payment.PaymentAdmission.Code.RATE_LIMITED, 2));
        assertThat(request(key)).isInstanceOf(Rejected.class);
        assertThat(rowsWritten()).isZero();

        gateClosed = Optional.empty();
        assertThat(request(key)).isInstanceOf(Accepted.class);
    }
}
