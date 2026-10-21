package com.paybridge.it.admission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.paybridge.application.payment.PaymentAdmission.Accepted;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

/**
 * FR-012: any failed statement rolls back everything, including the reservation. Failures are forced with database
 * triggers created by root, so the production code carries no test hook.
 */
class AtomicAdmissionRollbackIT extends AdmissionIT {

    private record Sabotage(String name, String table, String timing, String event, String condition) {}

    private static final List<Sabotage> STATEMENTS = List.of(
            new Sabotage("payment_request insert", "payment_request", "BEFORE", "INSERT", "1 = 1"),
            new Sabotage("payment insert", "payment", "BEFORE", "INSERT", "1 = 1"),
            new Sabotage("invoice pointer update", "invoice", "BEFORE", "UPDATE", "1 = 1"),
            new Sabotage("payment_event insert", "payment_event", "BEFORE", "INSERT", "1 = 1"),
            new Sabotage("COMMAND outbox insert", "outbox", "BEFORE", "INSERT", "NEW.destination = 'COMMAND'"),
            new Sabotage("STATUS outbox insert", "outbox", "BEFORE", "INSERT", "NEW.destination = 'STATUS'"),
            new Sabotage("audit insert", "audit_action", "BEFORE", "INSERT", "NEW.action = 'PAYMENT_ADMITTED'"));

    @Test
    void aFailureAtAnyStatementLeavesNoTraceAndTheKeyCanBeUsedAgain() {
        for (Sabotage s : STATEMENTS) {
            String key = key();
            rootJdbc.execute("CREATE TRIGGER t07_fail " + s.timing() + " " + s.event() + " ON " + s.table()
                    + " FOR EACH ROW BEGIN IF " + s.condition() + " THEN SIGNAL SQLSTATE '45000' "
                    + "SET MESSAGE_TEXT = 'forced failure'; END IF; END");
            try {
                assertThatThrownBy(() -> request(key)).as(s.name()).isInstanceOf(DataAccessException.class);
            } finally {
                rootJdbc.execute("DROP TRIGGER t07_fail");
            }

            assertThat(rowsWritten()).as("rows after failing %s", s.name()).isZero();
            assertThat(rootJdbc.queryForObject("SELECT active_payment_id FROM invoice WHERE id = ?", String.class, invoice))
                    .as("pointer after failing %s", s.name()).isNull();
            assertThat(rootJdbc.queryForObject("SELECT version FROM invoice WHERE id = ?", Long.class, invoice))
                    .as("invoice version after failing %s", s.name()).isZero();

            // the reservation was rolled back too, so the very same key now succeeds
            assertThat(request(key)).as("retry after %s", s.name()).isInstanceOf(Accepted.class);
            resetInvoice();
        }
    }

    /** Frees the invoice for the next sabotage round, child rows first; root bypasses the application on purpose. */
    private void resetInvoice() {
        rootJdbc.update("DELETE o FROM outbox o JOIN payment_event e ON e.event_id = o.event_id WHERE e.merchant_id = ?", merchant);
        rootJdbc.update("DELETE FROM payment_event WHERE merchant_id = ?", merchant);
        rootJdbc.update("UPDATE invoice SET active_payment_id = NULL, version = 0 WHERE id = ?", invoice);
        rootJdbc.update("DELETE FROM payment WHERE merchant_id = ?", merchant);
        rootJdbc.update("DELETE FROM payment_request WHERE merchant_id = ?", merchant);
        rootJdbc.update("DELETE FROM audit_action WHERE merchant_id = ? AND action = 'PAYMENT_ADMITTED'", merchant);
    }
}
