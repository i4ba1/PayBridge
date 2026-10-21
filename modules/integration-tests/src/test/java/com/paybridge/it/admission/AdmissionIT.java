package com.paybridge.it.admission;

import com.paybridge.application.payment.AdmissionActor;
import com.paybridge.application.payment.AdmitPayment;
import com.paybridge.application.payment.PaymentAdmission;
import com.paybridge.application.port.AdmissionGate;
import com.paybridge.application.port.AdmissionStore;
import com.paybridge.application.port.SystemFlags;
import com.paybridge.it.support.Fixtures;
import com.paybridge.it.support.MysqlSpringIT;
import com.paybridge.it.support.MysqlSupport;
import com.zaxxer.hikari.HikariDataSource;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Real store, real flags, fake gate and scenario resolver, connected as pb_core. Every test gets its own merchant,
 * user, invoice and method so tests never see each other's rows; counts are always scoped to the merchant.
 */
abstract class AdmissionIT extends MysqlSpringIT {
    static final String TOKEN = "tok_sim_card_default";
    static final String METHOD = "SIMULATED_CARD";

    static HikariDataSource root;
    static JdbcTemplate rootJdbc;

    @Autowired AdmissionStore store;
    @Autowired SystemFlags flags;
    @Autowired JdbcTemplate jdbc;

    final AtomicReference<String> scenarioRun = new AtomicReference<>("default");
    volatile Optional<AdmissionGate.Closed> gateClosed = Optional.empty();
    String merchant;
    String user;
    String invoice;
    AdmitPayment admit;

    @DynamicPropertySource
    static void noFlagCache(DynamicPropertyRegistry registry) {
        registry.add("paybridge.admission.flag-cache-ms", () -> "0");
    }

    @BeforeAll
    static void openRoot() {
        root = (HikariDataSource) MysqlSupport.rootDataSource();
        rootJdbc = new JdbcTemplate(root);
    }

    @AfterAll
    static void closeRoot() {
        root.close();
    }

    @BeforeEach
    void fixtures() {
        merchant = Fixtures.merchant(rootJdbc, "IDR");
        user = Fixtures.user(rootJdbc);
        invoice = Fixtures.invoice(rootJdbc, merchant, 150_000, "IDR");
        Fixtures.method(rootJdbc, merchant, METHOD, TOKEN);
        gateClosed = Optional.empty();
        scenarioRun.set("default");
        admit = useCase(settings(true, 3, 3));
    }

    @AfterEach
    void reopenAdmission() {
        rootJdbc.update("UPDATE system_flag SET flag_value = 'OPEN' WHERE flag_key = 'admission.capacity'");
        rootJdbc.update("UPDATE system_flag SET flag_value = 'OFF' WHERE flag_key = 'admission.maintenance'");
    }

    static AdmitPayment.Settings settings(boolean enabled, int lockWaitSeconds, int maxAttempts) {
        return new AdmitPayment.Settings(enabled, 128, lockWaitSeconds, maxAttempts, 5, 5);
    }

    AdmitPayment useCase(AdmitPayment.Settings settings) {
        return new AdmitPayment(store, m -> gateClosed, flags, m -> scenarioRun.get(), settings, AdmissionIT::sleep,
                () -> UUID.randomUUID().toString());
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    AdmissionActor actor() {
        return new AdmissionActor(merchant, user, "corr-1");
    }

    PaymentAdmission request(String key) {
        return admit.admit(actor(), invoice, TOKEN, key);
    }

    static String key() {
        return Fixtures.uuid();
    }

    long count(String table) {
        return rootJdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE merchant_id = ?", Long.class, merchant);
    }

    long outboxCount() {
        return rootJdbc.queryForObject("SELECT COUNT(*) FROM outbox o JOIN payment_event e ON e.event_id = o.event_id "
                + "WHERE e.merchant_id = ?", Long.class, merchant);
    }

    long admittedAuditCount() {
        return rootJdbc.queryForObject("SELECT COUNT(*) FROM audit_action WHERE merchant_id = ? AND action = 'PAYMENT_ADMITTED'",
                Long.class, merchant);
    }

    /** Nothing at all was written for this merchant. */
    long rowsWritten() {
        return count("payment_request") + count("payment") + count("payment_event") + outboxCount() + admittedAuditCount();
    }
}
