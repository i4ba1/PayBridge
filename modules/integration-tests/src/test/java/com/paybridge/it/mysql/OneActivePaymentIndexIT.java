package com.paybridge.it.mysql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.paybridge.it.support.Fixtures;
import com.paybridge.it.support.MysqlSupport;
import com.zaxxer.hikari.HikariDataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

/** FR-009: at most one non FAILED payment per invoice, enforced by ux_payment_one_active. */
@Tag("integration")
class OneActivePaymentIndexIT {
    private static HikariDataSource root;
    private static JdbcTemplate jdbc;
    private static String merchant;
    private static String user;

    @BeforeAll
    static void schema() {
        MysqlSupport.freshlyMigratedSchema();
        root = (HikariDataSource) MysqlSupport.rootDataSource();
        jdbc = new JdbcTemplate(root);
        merchant = Fixtures.merchant(jdbc, "IDR");
        user = Fixtures.user(jdbc);
    }

    @AfterAll
    static void close() {
        root.close();
    }

    private static String newInvoice() {
        return Fixtures.invoice(jdbc, merchant, 150_000, "IDR");
    }

    @Test
    void aSecondNonFailedPaymentForTheSameInvoiceIsRejected() {
        String invoice = newInvoice();
        Fixtures.payment(jdbc, merchant, invoice, user, "REQUESTED");
        for (String status : List.of("REQUESTED", "PROCESSING", "SUCCEEDED", "UNKNOWN")) {
            assertThatThrownBy(() -> Fixtures.payment(jdbc, merchant, invoice, user, status))
                    .as("second payment with status %s", status)
                    .isInstanceOf(DuplicateKeyException.class).hasMessageContaining("ux_payment_one_active");
        }
    }

    @Test
    void failedPaymentsDoNotBlockAndAnyNumberCanExist() {
        String invoice = newInvoice();
        Fixtures.payment(jdbc, merchant, invoice, user, "FAILED");
        Fixtures.payment(jdbc, merchant, invoice, user, "FAILED");
        Fixtures.payment(jdbc, merchant, invoice, user, "REQUESTED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment WHERE invoice_id = ?", Integer.class, invoice))
                .isEqualTo(3);
    }

    @Test
    void failingTheActivePaymentFreesTheInvoiceAndRevivingItIsRefused() {
        String invoice = newInvoice();
        String first = Fixtures.payment(jdbc, merchant, invoice, user, "PROCESSING");
        jdbc.update("UPDATE payment SET status = 'FAILED' WHERE id = ?", first);
        String second = Fixtures.payment(jdbc, merchant, invoice, user, "REQUESTED");

        // Moving the earlier failed payment back to a blocking state would give the invoice two active payments.
        assertThatThrownBy(() -> jdbc.update("UPDATE payment SET status = 'UNKNOWN' WHERE id = ?", first))
                .isInstanceOf(DuplicateKeyException.class);
        assertThat(jdbc.queryForObject("SELECT status FROM payment WHERE id = ?", String.class, second))
                .isEqualTo("REQUESTED");
    }

    @Test
    void differentInvoicesAreIndependent() {
        Fixtures.payment(jdbc, merchant, newInvoice(), user, "REQUESTED");
        Fixtures.payment(jdbc, merchant, newInvoice(), user, "REQUESTED");
    }

    @Test
    void concurrentAdmissionsForOneInvoiceProduceExactlyOnePayment() throws Exception {
        String invoice = newInvoice();
        int threads = 12;
        var start = new CountDownLatch(1);
        var created = new AtomicInteger();
        var rejected = new AtomicInteger();
        var errors = new ArrayList<Throwable>();
        var pool = Executors.newFixedThreadPool(threads);
        try {
            var futures = new ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    try {
                        start.await();
                        Fixtures.payment(jdbc, merchant, invoice, user, "REQUESTED");
                        created.incrementAndGet();
                    } catch (DuplicateKeyException e) {
                        rejected.incrementAndGet();
                    } catch (Throwable t) {
                        synchronized (errors) {
                            errors.add(t);
                        }
                    }
                    return null;
                }));
            }
            start.countDown();
            for (var f : futures) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(errors).isEmpty();
        assertThat(created.get()).isEqualTo(1);
        assertThat(rejected.get()).isEqualTo(threads - 1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment WHERE invoice_id = ?", Integer.class, invoice))
                .isEqualTo(1);
    }

    @Test
    void listIndexesExistWithTheDocumentedShape() {
        List<String> keys = jdbc.queryForList("SELECT DISTINCT index_name FROM information_schema.statistics "
                + "WHERE table_schema = 'paybridge' AND table_name = 'payment'", String.class);
        assertThat(keys).contains("ux_payment_one_active", "ux_payment_provider_key", "ix_payment_list",
                "ix_payment_status_list", "ix_payment_status_created", "ix_payment_invoice", "ix_payment_provider_ref");
        // ix_payment_list is (merchant_id, created_at DESC, id DESC): both trailing parts are descending.
        List<String> collation = jdbc.queryForList("SELECT collation FROM information_schema.statistics WHERE "
                + "table_schema = 'paybridge' AND table_name = 'payment' AND index_name = 'ix_payment_list' "
                + "ORDER BY seq_in_index", String.class);
        assertThat(collation).containsExactly("A", "D", "D");
    }
}
