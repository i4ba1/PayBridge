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
            // The pointer is still NULL, so the reject-only pre-check falls through and the transaction blocks on FOR UPDATE.
            CompletableFuture<PaymentAdmission> waiting = CompletableFuture.supplyAsync(() -> request(key()));
            assertThat(awaitLockWaitOnTheInvoice()).as("the admission is blocked on the invoice lock (data_lock_waits)")
                    .isTrue();
            assertThat(waiting).isNotDone();

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

    /**
     * Polls InnoDB (deadline 10 s) until a lock request on the invoice table is waiting. performance_schema.data_lock_waits
     * is used because information_schema.innodb_trx did not list the waiting pb_core transaction on MySQL 8.4.11.
     */
    private boolean awaitLockWaitOnTheInvoice() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Long waiting = rootJdbc.queryForObject("SELECT COUNT(*) FROM performance_schema.data_lock_waits w "
                    + "JOIN performance_schema.data_locks l ON l.ENGINE_LOCK_ID = w.REQUESTING_ENGINE_LOCK_ID "
                    + "WHERE l.OBJECT_SCHEMA = DATABASE() AND l.OBJECT_NAME = 'invoice'", Long.class);
            if (waiting != null && waiting > 0) {
                return true;
            }
            Thread.sleep(20);
        }
        return false;
    }
}
