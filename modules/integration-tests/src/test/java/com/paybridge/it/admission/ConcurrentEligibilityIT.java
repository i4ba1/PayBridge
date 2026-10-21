package com.paybridge.it.admission;

import static org.assertj.core.api.Assertions.assertThat;

import com.paybridge.application.payment.PaymentAdmission;
import com.paybridge.application.payment.PaymentAdmission.Code;
import com.paybridge.application.payment.PaymentAdmission.Rejected;
import com.paybridge.it.support.Fixtures;
import java.sql.Connection;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Eligibility is decided from the invoice row read under its lock, not from an earlier unlocked read. */
class ConcurrentEligibilityIT extends AdmissionIT {

    @Test
    void anAdmissionWaitingOnTheInvoiceLockSeesThePointerSetByTheLockHolder() throws Exception {
        // A FAILED payment does not trip ux_payment_one_active, so only the re-read pointer can refuse the request.
        String other = Fixtures.payment(rootJdbc, merchant, invoice, user, "FAILED");

        try (Connection holder = root.getConnection()) {
            holder.setAutoCommit(false);
            try (var st = holder.prepareStatement("SELECT id FROM invoice WHERE id = ? FOR UPDATE")) {
                st.setString(1, invoice);
                st.executeQuery().close();
            }
            CompletableFuture<PaymentAdmission> waiting = CompletableFuture.supplyAsync(() -> request(key()));
            Thread.sleep(500);
            assertThat(waiting).as("the admission is blocked on the invoice lock").isNotDone();

            try (var st = holder.prepareStatement("UPDATE invoice SET active_payment_id = ?, version = version + 1 WHERE id = ?")) {
                st.setString(1, other);
                st.setString(2, invoice);
                st.executeUpdate();
            }
            holder.commit();

            var result = waiting.get(10, TimeUnit.SECONDS);
            assertThat(result).isEqualTo(Rejected.of(Code.INVOICE_NOT_ELIGIBLE));
        }
        assertThat(count("payment_request")).as("the reservation was rolled back").isZero();
        assertThat(rowsWritten()).as("only the planted FAILED payment").isOne();
    }
}
