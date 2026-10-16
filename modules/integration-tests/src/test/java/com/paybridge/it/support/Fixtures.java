package com.paybridge.it.support;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Plain SQL row builders for constraint tests; they bypass the application on purpose. */
public final class Fixtures {
    private Fixtures() {}

    public static String uuid() {
        return UUID.randomUUID().toString();
    }

    public static String merchant(JdbcTemplate jdbc, String currency) {
        String id = uuid();
        jdbc.update("INSERT INTO merchant (id, code, name, currency, created_at, updated_at) "
                + "VALUES (?, ?, 'Test merchant', ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))",
                id, "m-" + id.substring(0, 8), currency);
        return id;
    }

    public static String user(JdbcTemplate jdbc) {
        String id = uuid();
        jdbc.update("INSERT INTO app_user (id, username, display_name, password_hash, created_at, updated_at) "
                + "VALUES (?, ?, 'Test user', '{noop}x', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))",
                id, "user-" + id.substring(0, 8));
        return id;
    }

    public static String invoice(JdbcTemplate jdbc, String merchantId, long amountMinor, String currency) {
        String id = uuid();
        jdbc.update("INSERT INTO invoice (id, merchant_id, reference, amount_minor, currency, created_at) "
                + "VALUES (?, ?, ?, ?, ?, UTC_TIMESTAMP(6))", id, merchantId, "INV-" + id.substring(0, 8),
                amountMinor, currency);
        return id;
    }

    public static String payment(JdbcTemplate jdbc, String merchantId, String invoiceId, String userId, String status) {
        String id = uuid();
        jdbc.update("INSERT INTO payment (id, merchant_id, invoice_id, method_code, method_token, amount_minor, "
                + "currency, status, aggregate_version, provider_key, correlation_id, requested_by, created_at, "
                + "updated_at) SELECT ?, ?, ?, 'SIMULATED_CARD', 'tok_sim_card_default', amount_minor, currency, ?, 1, "
                + "CONCAT('pbk-', ?), 'corr-1', ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6) FROM invoice WHERE id = ?",
                id, merchantId, invoiceId, status, id, userId, invoiceId);
        return id;
    }
}
