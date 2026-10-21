package com.paybridge.it.admission;

import static org.assertj.core.api.Assertions.assertThat;

import com.paybridge.application.payment.PaymentAdmission.Code;
import com.paybridge.application.payment.PaymentAdmission.Rejected;
import com.paybridge.it.support.Fixtures;
import org.junit.jupiter.api.Test;

/**
 * With the application lock bypassed (a blocking payment exists but the invoice pointer is NULL, as after a bad restore),
 * ux_payment_one_active still refuses the second active payment and the whole admission rolls back.
 */
class IndexBackstopIT extends AdmissionIT {

    @Test
    void theUniqueIndexRefusesASecondActivePaymentEvenWhenThePointerIsMissing() {
        Fixtures.payment(rootJdbc, merchant, invoice, user, "REQUESTED");

        var result = request(key());

        assertThat(result).isEqualTo(Rejected.of(Code.INVOICE_NOT_ELIGIBLE));
        assertThat(count("payment")).as("only the planted payment").isOne();
        assertThat(count("payment_request")).isZero();
        assertThat(count("payment_event")).isZero();
        assertThat(outboxCount()).isZero();
        assertThat(admittedAuditCount()).isZero();
    }
}
