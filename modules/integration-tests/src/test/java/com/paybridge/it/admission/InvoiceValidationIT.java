package com.paybridge.it.admission;

import static org.assertj.core.api.Assertions.assertThat;

import com.paybridge.application.payment.AdmissionActor;
import com.paybridge.application.payment.PaymentAdmission.Accepted;
import com.paybridge.application.payment.PaymentAdmission.Code;
import com.paybridge.application.payment.PaymentAdmission.Rejected;
import com.paybridge.it.support.Fixtures;
import org.junit.jupiter.api.Test;

class InvoiceValidationIT extends AdmissionIT {

    @Test
    void amountAndCurrencyComeOnlyFromTheInvoice() {
        String usd = Fixtures.invoice(rootJdbc, merchant, 12_345, "USD");
        var accepted = (Accepted) admit.admit(actor(), usd, TOKEN, key());
        var row = rootJdbc.queryForMap("SELECT amount_minor, currency FROM payment WHERE id = ?", accepted.paymentId());
        assertThat(row).containsEntry("amount_minor", 12_345L).containsEntry("currency", "USD");
    }

    @Test
    void anotherMerchantsInvoiceAndAMissingInvoiceAreIndistinguishable() {
        String otherMerchant = Fixtures.merchant(rootJdbc, "IDR");
        String foreign = Fixtures.invoice(rootJdbc, otherMerchant, 1_000, "IDR");

        var foreignResult = admit.admit(actor(), foreign, TOKEN, key());
        var missingResult = admit.admit(actor(), Fixtures.uuid(), TOKEN, key());

        assertThat(foreignResult).isEqualTo(Rejected.of(Code.NOT_FOUND)).isEqualTo(missingResult);
        assertThat(rowsWritten()).isZero();
        assertThat(rootJdbc.queryForObject("SELECT active_payment_id FROM invoice WHERE id = ?", String.class, foreign)).isNull();
    }

    @Test
    void badTokensAreRejectedAndNothingIsWritten() {
        for (String bad : new String[] {"tok_does_not_exist", "", " ", "tok sim", "x".repeat(129), "tök", "tok'; DROP TABLE payment;--"}) {
            var result = admit.admit(actor(), invoice, bad, key());
            assertThat(result).as("token '%s'", bad).isInstanceOf(Rejected.class);
            assertThat(((Rejected) result).code()).isEqualTo(Code.VALIDATION_ERROR);
        }
        assertThat(rowsWritten()).isZero();
    }

    @Test
    void aMethodDisabledForTheMerchantIsRejected() {
        rootJdbc.update("UPDATE merchant_method SET enabled = 0 WHERE merchant_id = ? AND method_code = ?", merchant, METHOD);
        var result = (Rejected) admit.admit(actor(), invoice, TOKEN, key());
        assertThat(result.code()).isEqualTo(Code.VALIDATION_ERROR);
        assertThat(rowsWritten()).isZero();
    }

    @Test
    void aTokenOfAnotherMerchantsMethodIsRejected() {
        String lonely = Fixtures.merchant(rootJdbc, "IDR");
        String lonelyInvoice = Fixtures.invoice(rootJdbc, lonely, 5_000, "IDR");
        var result = (Rejected) admit.admit(new AdmissionActor(lonely, user, "c"), lonelyInvoice, TOKEN, key());
        assertThat(result.code()).as("this merchant has no merchant_method row").isEqualTo(Code.VALIDATION_ERROR);
    }
}
