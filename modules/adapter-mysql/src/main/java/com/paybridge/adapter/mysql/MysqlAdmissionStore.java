package com.paybridge.adapter.mysql;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paybridge.application.port.AdmissionStore;
import com.paybridge.application.port.AuditStore;
import com.paybridge.contracts.PaymentEventEnvelope;
import com.paybridge.contracts.PaymentEventTypes;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The DMR 6.1 admission transaction in plain SQL. The statement order IS the lock order (reservation, invoice,
 * payment, invoice pointer, event, outbox, audit), so it must not be reordered. JPA is deliberately not used here:
 * the order, the MySQL error numbers (1062, 1205, 1213) and the session lock wait timeout have to be visible.
 */
@Repository
public class MysqlAdmissionStore implements AdmissionStore {
    private static final Logger log = LoggerFactory.getLogger(MysqlAdmissionStore.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final AuditStore audit;

    MysqlAdmissionStore(JdbcTemplate jdbc, PlatformTransactionManager transactionManager, AuditStore audit) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
        this.audit = audit;
    }

    @Override
    public Optional<Replay> findReplay(String merchantId, String idempotencyKey) {
        List<Replay> rows = jdbc.query("SELECT r.payment_id, r.request_hash, p.status, p.aggregate_version "
                + "FROM payment_request r JOIN payment p ON p.id = r.payment_id "
                + "WHERE r.merchant_id = ? AND r.operation = 'REQUEST_PAYMENT' AND r.idempotency_key = ?",
                (rs, i) -> new Replay(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4)),
                merchantId, idempotencyKey);
        return rows.stream().findFirst();
    }

    @Override
    public Optional<MethodChoice> resolveMethod(String merchantId, String token) {
        List<MethodChoice> rows = jdbc.query("SELECT t.method_code FROM method_token t "
                + "JOIN method_catalog c ON c.method_code = t.method_code AND c.enabled = 1 "
                + "JOIN merchant_method m ON m.method_code = t.method_code AND m.merchant_id = ? AND m.enabled = 1 "
                + "WHERE t.token = ? AND t.enabled = 1", (rs, i) -> new MethodChoice(rs.getString(1)), merchantId, token);
        return rows.stream().findFirst();
    }

    @Override
    public AdmissionResult admit(AdmissionRequest request) {
        try {
            return tx.execute(status -> run(request, status));
        } catch (DuplicateKeyException e) {
            String message = String.valueOf(e.getMessage());
            if (message.contains("ux_payment_request")) {
                // We lost the key race: the winner has committed (or we would still be waiting). Read it in a new transaction.
                return findReplay(request.merchantId(), request.idempotencyKey())
                        .<AdmissionResult>map(AdmissionResult.Duplicate::new).orElseGet(AdmissionResult.Transient::new);
            }
            if (message.contains("ux_payment_one_active")) {
                log.error("ux_payment_one_active refused an admission the invoice pointer allowed; run the recovery checks");
                return new AdmissionResult.NotEligible();
            }
            throw e;
        } catch (PessimisticLockingFailureException e) {
            // 1205 lock wait timeout or 1213 deadlock: the transaction was rolled back, nothing was written.
            return new AdmissionResult.Transient();
        }
    }

    private AdmissionResult run(AdmissionRequest r, TransactionStatus status) {
        limitLockWait(r.lockWaitTimeoutSeconds());

        // 1. reservation
        jdbc.update("INSERT INTO payment_request (id, merchant_id, operation, idempotency_key, request_hash, payment_id, "
                + "requested_by, created_at) VALUES (?, ?, 'REQUEST_PAYMENT', ?, ?, ?, ?, UTC_TIMESTAMP(6))",
                UUID.randomUUID().toString(), r.merchantId(), r.idempotencyKey(), r.requestHash(), r.paymentId(), r.userId());

        // 2. invoice lock, then eligibility from the row just locked
        List<InvoiceRow> invoices = jdbc.query("SELECT amount_minor, currency, reference, active_payment_id FROM invoice "
                + "WHERE id = ? AND merchant_id = ? FOR UPDATE",
                (rs, i) -> new InvoiceRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)),
                r.invoiceId(), r.merchantId());
        if (invoices.isEmpty()) {
            status.setRollbackOnly();
            return new AdmissionResult.InvoiceNotFound();
        }
        InvoiceRow invoice = invoices.get(0);
        if (invoice.activePaymentId() != null) {
            status.setRollbackOnly();
            return new AdmissionResult.NotEligible();
        }

        // 3. payment (amount and currency come from the invoice only)
        jdbc.update("INSERT INTO payment (id, merchant_id, invoice_id, method_code, method_token, amount_minor, currency, "
                + "status, aggregate_version, version, provider_key, scenario_run_id, correlation_id, requested_by, "
                + "created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, 'REQUESTED', 1, 0, CONCAT('pbk-', ?), ?, ?, ?, "
                + "UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))",
                r.paymentId(), r.merchantId(), r.invoiceId(), r.methodCode(), r.methodToken(), invoice.amountMinor(),
                invoice.currency(), r.paymentId(), r.scenarioRunId(), r.correlationId(), r.userId());

        // 4. invoice pointer
        int pointed = jdbc.update("UPDATE invoice SET active_payment_id = ?, version = version + 1 "
                + "WHERE id = ? AND active_payment_id IS NULL", r.paymentId(), r.invoiceId());
        if (pointed != 1) {
            throw new IllegalStateException("invoice pointer update changed " + pointed + " rows under the invoice lock");
        }

        // 5. STATE event, 6. two outbox rows
        jdbc.update("INSERT INTO payment_event (event_id, payment_id, merchant_id, event_class, event_type, schema_version, "
                + "aggregate_version, occurred_at, correlation_id, payload) "
                + "VALUES (?, ?, ?, 'STATE', ?, ?, 1, UTC_TIMESTAMP(6), ?, CAST(? AS JSON))",
                r.eventId(), r.paymentId(), r.merchantId(), PaymentEventTypes.PAYMENT_REQUESTED,
                PaymentEventEnvelope.CURRENT_SCHEMA_VERSION, r.correlationId(), requestedPayload(r, invoice));
        jdbc.update("INSERT INTO outbox (event_id, destination, exchange, routing_key, status, due_at, created_at) "
                + "VALUES (?, 'COMMAND', 'paybridge.commands', 'payment.collect.v1', 'PENDING', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))",
                r.eventId());
        jdbc.update("INSERT INTO outbox (event_id, destination, exchange, routing_key, status, due_at, created_at) "
                + "VALUES (?, 'STATUS', 'paybridge.events', 'payment.status.v1', 'PENDING', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))",
                r.eventId());

        // 7. audit, inside the same transaction (MysqlAuditStore requires one)
        audit.record(AuditStore.Entry.of(AuditStore.ActorType.USER, r.userId(), r.merchantId(), "PAYMENT_ADMITTED",
                "payment", r.paymentId(), AuditStore.Result.SUCCESS, r.correlationId()));
        return new AdmissionResult.Created(r.paymentId());
    }

    /** Shortens the lock wait for this transaction only and restores the pooled connection before it is returned. */
    private void limitLockWait(int seconds) {
        Integer previous = jdbc.queryForObject("SELECT @@SESSION.innodb_lock_wait_timeout", Integer.class);
        jdbc.execute("SET SESSION innodb_lock_wait_timeout = " + seconds);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void beforeCompletion() {
                try {
                    jdbc.execute("SET SESSION innodb_lock_wait_timeout = " + previous);
                } catch (RuntimeException e) {
                    log.warn("could not restore innodb_lock_wait_timeout: {}", e.getClass().getSimpleName());
                }
            }
        });
    }

    private static String requestedPayload(AdmissionRequest r, InvoiceRow invoice) {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("invoiceId", r.invoiceId());
        payload.put("invoiceReference", invoice.reference());
        payload.put("methodCode", r.methodCode());
        payload.put("amountMinor", Long.toString(invoice.amountMinor()));
        payload.put("currency", invoice.currency());
        payload.put("providerKey", "pbk-" + r.paymentId());
        try {
            return JSON.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("event payload is not serializable", e);
        }
    }

    private record InvoiceRow(long amountMinor, String currency, String reference, String activePaymentId) {}
}
