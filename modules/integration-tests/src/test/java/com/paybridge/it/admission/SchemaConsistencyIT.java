package com.paybridge.it.admission;

import static org.assertj.core.api.Assertions.assertThat;

import com.paybridge.application.payment.PaymentAdmission.Accepted;
import com.paybridge.application.port.ConsistencyCheck;
import com.paybridge.core.admission.InvoicePointersHealthIndicator;
import com.paybridge.it.support.Fixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Status;
import org.springframework.jdbc.core.ConnectionCallback;

class SchemaConsistencyIT extends AdmissionIT {
    @Autowired ConsistencyCheck check;

    /** The check is global, so every test starts from, and must leave behind, a clean database. */
    @BeforeEach
    void startsClean() {
        var report = check.run();
        assertThat(report.clean()).as(report.toString()).isTrue();
    }

    private InvoicePointersHealthIndicator indicator() {
        return new InvoicePointersHealthIndicator(check);
    }

    /** FOREIGN_KEY_CHECKS is per connection, so the update must run on the same connection that disabled it. */
    private void pointInvoiceAtMissingPayment(String invoiceId) {
        rootJdbc.execute((ConnectionCallback<Void>) c -> {
            try (var st = c.createStatement()) {
                st.execute("SET FOREIGN_KEY_CHECKS = 0");
                st.executeUpdate("UPDATE invoice SET active_payment_id = '" + Fixtures.uuid() + "' WHERE id = '" + invoiceId + "'");
                st.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
            return null;
        });
    }

    @Test
    void cleanDataReportsNothingAndTheIndicatorIsUp() {
        request(key());
        var report = check.run();
        assertThat(report.clean()).as(report.toString()).isTrue();
        var indicator = indicator();
        indicator.runCheck();
        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void aPointerToAMissingPaymentIsReportedAndMakesReadinessDown() {
        String broken = Fixtures.invoice(rootJdbc, merchant, 1_000, "IDR");
        pointInvoiceAtMissingPayment(broken);
        try {
            var report = check.run();
            assertThat(report.pointersToMissingFailedOrForeign()).isPositive();
            var indicator = indicator();
            indicator.runCheck();
            var health = indicator.health();
            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
            assertThat(health.getDetails()).containsKey("pointersToMissingFailedOrForeign");
            assertThat(health.getDetails().toString()).doesNotContain(broken);
        } finally {
            rootJdbc.update("UPDATE invoice SET active_payment_id = NULL WHERE id = ?", broken);
        }
    }

    @Test
    void anActivePaymentWithoutAPointerIsReported() {
        String invoiceNoPointer = Fixtures.invoice(rootJdbc, merchant, 2_000, "IDR");
        String payment = Fixtures.payment(rootJdbc, merchant, invoiceNoPointer, user, "REQUESTED");
        try {
            assertThat(check.run().activePaymentsNotPointedTo()).isPositive();
        } finally {
            rootJdbc.update("UPDATE payment SET status = 'FAILED' WHERE id = ?", payment);
        }
        assertThat(check.run().activePaymentsNotPointedTo()).isZero();
    }

    @Test
    void aReservationWithoutAPaymentIsReported() {
        String reservation = Fixtures.uuid();
        rootJdbc.update("INSERT INTO payment_request (id, merchant_id, operation, idempotency_key, request_hash, payment_id, "
                + "requested_by, created_at) VALUES (?, ?, 'REQUEST_PAYMENT', ?, ?, ?, ?, UTC_TIMESTAMP(6))",
                reservation, merchant, Fixtures.uuid(), "a".repeat(64), Fixtures.uuid(), user);
        try {
            assertThat(check.run().reservationsWithoutPayment()).isPositive();
        } finally {
            rootJdbc.update("DELETE FROM payment_request WHERE id = ?", reservation);
        }
        assertThat(check.run().reservationsWithoutPayment()).isZero();
    }

    @Test
    void theIndicatorIsUnknownUntilTheCheckHasRun() {
        assertThat(indicator().health().getStatus()).isEqualTo(Status.UNKNOWN);
    }

    @Test
    void anAdmittedPaymentIsConsistent() {
        var accepted = (Accepted) request(key());
        assertThat(accepted.status()).isEqualTo("REQUESTED");
        assertThat(check.run().clean()).isTrue();
    }
}
