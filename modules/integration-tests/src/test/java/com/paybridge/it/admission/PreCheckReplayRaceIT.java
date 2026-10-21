package com.paybridge.it.admission;

import static org.assertj.core.api.Assertions.assertThat;

import com.paybridge.adapter.mysql.MysqlAdmissionStore;
import com.paybridge.application.port.AdmissionStore.AdmissionRequest;
import com.paybridge.application.port.AdmissionStore.AdmissionResult;
import com.paybridge.application.port.AuditStore;
import com.paybridge.it.support.Fixtures;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * FR-011 under the pre-check race: request B (same key and payload as an in-flight request A) misses A's reservation in
 * its first replay read, then A commits, then B's unlocked pointer read sees A's pointer. B must answer with A's payment
 * as a replay, not INVOICE_NOT_ELIGIBLE. The interleaving is forced deterministically: A is admitted (and committed) by
 * the real store exactly between B's replay read and B's pointer read.
 */
class PreCheckReplayRaceIT extends AdmissionIT {
    private static final String POINTER_READ = "SELECT active_payment_id IS NOT NULL FROM invoice";

    @Autowired PlatformTransactionManager transactionManager;
    @Autowired AuditStore audit;

    private AdmissionRequest storeRequest(String key) {
        return new AdmissionRequest(Fixtures.uuid(), Fixtures.uuid(), merchant, user, invoice, key, "a".repeat(64), METHOD,
                TOKEN, "default", "corr-1", 3);
    }

    /** A JdbcTemplate on the same pool that runs {@code beforePointerRead} once, just before the pre-check's pointer read. */
    private static final class InterleavingJdbc extends JdbcTemplate {
        private final Runnable beforePointerRead;
        private final AtomicBoolean fired = new AtomicBoolean();

        InterleavingJdbc(JdbcTemplate pool, Runnable beforePointerRead) {
            super(pool.getDataSource());
            this.beforePointerRead = beforePointerRead;
        }

        @Override
        public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
            if (sql.startsWith(POINTER_READ) && fired.compareAndSet(false, true)) {
                beforePointerRead.run();
            }
            return super.query(sql, rowMapper, args);
        }

        boolean fired() {
            return fired.get();
        }
    }

    private MysqlAdmissionStore storeOver(JdbcTemplate template) throws Exception {
        var constructor = MysqlAdmissionStore.class.getDeclaredConstructor(JdbcTemplate.class,
                PlatformTransactionManager.class, AuditStore.class);
        constructor.setAccessible(true);
        return constructor.newInstance(template, transactionManager, audit);
    }

    @Test
    void aSameKeyRequestWhoseWinnerCommitsBetweenThePreCheckReadsIsAReplayNotNotEligible() throws Exception {
        String key = key();
        AdmissionRequest a = storeRequest(key);
        AdmissionRequest b = storeRequest(key);   // same key and hash, its own payment id
        var winner = new AdmissionResult[1];
        var jdbcB = new InterleavingJdbc(jdbc, () -> winner[0] = store.admit(a));

        assertThat(store.findReplay(merchant, key)).as("nothing is reserved before B starts").isEmpty();
        AdmissionResult result = storeOver(jdbcB).admit(b);

        assertThat(jdbcB.fired()).as("A was admitted between B's replay read and B's pointer read").isTrue();
        assertThat(winner[0]).isEqualTo(new AdmissionResult.Created(a.paymentId()));
        assertThat(result).as("FR-011: the same key and payload get the winner's payment").isInstanceOf(AdmissionResult.Duplicate.class);
        assertThat(((AdmissionResult.Duplicate) result).existing().paymentId()).isEqualTo(a.paymentId());
        assertThat(count("payment_request")).isOne();
        assertThat(count("payment")).isOne();
    }
}
