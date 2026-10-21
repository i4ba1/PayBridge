package com.paybridge.it.admission;

import static org.assertj.core.api.Assertions.assertThat;

import com.paybridge.application.payment.PaymentAdmission.Accepted;
import com.paybridge.application.payment.PaymentAdmission.Code;
import com.paybridge.application.payment.PaymentAdmission.Rejected;
import java.sql.Connection;
import org.junit.jupiter.api.Test;

class LockWaitTimeoutIT extends AdmissionIT {

    @Test
    void aHeldInvoiceLockIsTemporarilyUnavailableWithoutResidueAndWithoutLeakingTheTimeout() throws Exception {
        admit = useCase(settings(true, 1, 2));
        String key = key();
        long started;
        try (Connection holder = root.getConnection()) {
            holder.setAutoCommit(false);
            try (var st = holder.prepareStatement("SELECT id FROM invoice WHERE id = ? FOR UPDATE")) {
                st.setString(1, invoice);
                st.executeQuery().close();
            }
            started = System.nanoTime();
            var result = request(key);
            long elapsedMillis = (System.nanoTime() - started) / 1_000_000;

            assertThat(result).isEqualTo(Rejected.of(Code.TEMPORARILY_UNAVAILABLE, 5));
            assertThat(elapsedMillis).as("two attempts of about one second each, not the 50 second server default")
                    .isBetween(1_500L, 15_000L);
            assertThat(rowsWritten()).as("the reservation was rolled back").isZero();
            holder.rollback();
        }

        // the 1 second session timeout must not stay on the pooled connection
        for (int i = 0; i < 16; i++) {
            assertThat(jdbc.queryForObject("SELECT @@SESSION.innodb_lock_wait_timeout", Integer.class)).isGreaterThan(1);
        }
        assertThat(request(key)).as("the same key works once the lock is free").isInstanceOf(Accepted.class);
    }
}
