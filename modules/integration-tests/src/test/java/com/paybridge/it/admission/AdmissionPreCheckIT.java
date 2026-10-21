package com.paybridge.it.admission;

import static org.assertj.core.api.Assertions.assertThat;

import com.paybridge.application.port.AdmissionStore.AdmissionRequest;
import com.paybridge.application.port.AdmissionStore.AdmissionResult;
import com.paybridge.it.support.Fixtures;
import java.sql.Connection;
import org.junit.jupiter.api.Test;

/**
 * The store's reject-only pre-check answers before the transaction: no reservation insert is attempted (a trigger that
 * fails every payment_request insert stays silent) and no invoice lock is requested (a held FOR UPDATE does not delay it).
 */
class AdmissionPreCheckIT extends AdmissionIT {

    private AdmissionRequest storeRequest(String key) {
        return new AdmissionRequest(Fixtures.uuid(), Fixtures.uuid(), merchant, user, invoice, key, "a".repeat(64), METHOD,
                TOKEN, "default", "corr-1", 1);
    }

    @Test
    void anInvoiceAlreadyPointedIsNotEligibleWithoutAReservationOrALockWait() throws Exception {
        String failed = Fixtures.payment(rootJdbc, merchant, invoice, user, "FAILED");
        rootJdbc.update("UPDATE invoice SET active_payment_id = ? WHERE id = ?", failed, invoice);

        AdmissionResult result = withoutWritesOrLocks(() -> store.admit(storeRequest(key())));

        assertThat(result).isEqualTo(new AdmissionResult.NotEligible());
        assertThat(count("payment_request")).isZero();
    }

    @Test
    void aSameKeyRequestWhoseWinnerCommittedIsADuplicateNotNotEligible() throws Exception {
        String key = key();
        var won = store.admit(storeRequest(key));
        assertThat(won).isInstanceOf(AdmissionResult.Created.class);
        String paymentId = ((AdmissionResult.Created) won).paymentId();

        AdmissionResult result = withoutWritesOrLocks(() -> store.admit(storeRequest(key)));

        assertThat(result).isInstanceOf(AdmissionResult.Duplicate.class);
        assertThat(((AdmissionResult.Duplicate) result).existing().paymentId()).isEqualTo(paymentId);
        assertThat(count("payment_request")).isOne();
    }

    private interface Admission {
        AdmissionResult run();
    }

    /** Runs the call while every payment_request insert fails and the invoice row is locked by another session. */
    private AdmissionResult withoutWritesOrLocks(Admission admission) throws Exception {
        rootJdbc.execute("CREATE TRIGGER t07_precheck BEFORE INSERT ON payment_request FOR EACH ROW "
                + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'reservation insert attempted'");
        try (Connection holder = root.getConnection()) {
            holder.setAutoCommit(false);
            try (var st = holder.prepareStatement("SELECT id FROM invoice WHERE id = ? FOR UPDATE")) {
                st.setString(1, invoice);
                st.executeQuery().close();
            }
            long started = System.nanoTime();
            AdmissionResult result = admission.run();
            long elapsedMillis = (System.nanoTime() - started) / 1_000_000;
            assertThat(elapsedMillis).as("no wait on the held invoice lock (lock wait timeout is 1 second)").isLessThan(900L);
            holder.rollback();
            return result;
        } finally {
            rootJdbc.execute("DROP TRIGGER t07_precheck");
        }
    }
}
