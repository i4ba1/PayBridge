package com.paybridge.adapter.mysql;

import com.paybridge.application.port.ConsistencyCheck;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class MysqlConsistencyCheck implements ConsistencyCheck {
    private final JdbcTemplate jdbc;

    MysqlConsistencyCheck(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Report run() {
        return new Report(
                // A. pointer to a missing, FAILED or foreign payment
                count("SELECT COUNT(*) FROM invoice i LEFT JOIN payment p ON p.id = i.active_payment_id "
                        + "WHERE i.active_payment_id IS NOT NULL AND (p.id IS NULL OR p.status = 'FAILED' OR p.invoice_id <> i.id)"),
                // B. non-FAILED payment that its invoice does not point to
                count("SELECT COUNT(*) FROM payment p JOIN invoice i ON i.id = p.invoice_id "
                        + "WHERE p.status <> 'FAILED' AND (i.active_payment_id IS NULL OR i.active_payment_id <> p.id)"),
                // C. reservation without payment
                count("SELECT COUNT(*) FROM payment_request r LEFT JOIN payment p ON p.id = r.payment_id WHERE p.id IS NULL"));
    }

    private long count(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }
}
