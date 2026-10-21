package com.paybridge.adapter.mysql;

import com.paybridge.application.port.SeedStore;
import java.util.Optional;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Seed writes. Timestamps come from the database clock (UTC_TIMESTAMP(6)), never the application clock. The duplicate
 * key guard is {@code ON DUPLICATE KEY UPDATE <key> = <key>}, which can never change a stored value.
 */
@Repository
public class MysqlSeedStore implements SeedStore {

    private final JdbcTemplate jdbc;
    private final DataSource dataSource;
    private final TransactionTemplate tx;

    MysqlSeedStore(JdbcTemplate jdbc, DataSource dataSource, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.dataSource = dataSource;
        this.tx = tx;
    }

    @Override
    public <T> Optional<T> withLock(String name, int timeoutSeconds, Supplier<T> work) {
        // GET_LOCK belongs to one connection, so that connection is held until the lock is released.
        return jdbc.execute((ConnectionCallback<Optional<T>>) con -> {
            Integer got = queryInt(con, "SELECT GET_LOCK(?, ?)", name, timeoutSeconds);
            if (got == null || got != 1) {
                return Optional.empty();
            }
            try {
                return Optional.ofNullable(work.get());
            } finally {
                queryInt(con, "SELECT RELEASE_LOCK(?)", name);
            }
        });
    }

    private static Integer queryInt(java.sql.Connection con, String sql, Object... args) throws java.sql.SQLException {
        try (var ps = con.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            try (var rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                int value = rs.getInt(1);
                return rs.wasNull() ? null : value;
            }
        }
    }

    @Override
    public <T> T inTransaction(Supplier<T> work) {
        return tx.execute(status -> work.get());
    }

    private boolean absent(String sql, Object... args) {
        return jdbc.queryForList(sql, Integer.class, args).isEmpty();
    }

    @Override
    public boolean insertMethod(String methodCode, String displayName, String settlement) {
        if (!absent("SELECT 1 FROM method_catalog WHERE method_code = ?", methodCode)) {
            return false;
        }
        jdbc.update("INSERT INTO method_catalog (method_code, display_name, settlement, enabled) VALUES (?, ?, ?, 1) "
                + "ON DUPLICATE KEY UPDATE method_code = method_code", methodCode, displayName, settlement);
        return true;
    }

    @Override
    public boolean insertMethodToken(String token, String methodCode, String description) {
        if (!absent("SELECT 1 FROM method_token WHERE token = ?", token)) {
            return false;
        }
        jdbc.update("INSERT INTO method_token (token, method_code, description, enabled) VALUES (?, ?, ?, 1) "
                + "ON DUPLICATE KEY UPDATE token = token", token, methodCode, description);
        return true;
    }

    @Override
    public boolean ensureBootstrapRow() {
        return jdbc.update("INSERT IGNORE INTO bootstrap_state (id, state, token_hash, created_at) "
                + "VALUES (1, 'PENDING', NULL, UTC_TIMESTAMP(6))") == 1;
    }

    @Override
    public int rearmBootstrapToken(String tokenHashHex, int ttlHours) {
        return jdbc.update("UPDATE bootstrap_state SET state = 'PENDING', token_hash = ?, "
                + "token_expires_at = DATE_ADD(UTC_TIMESTAMP(6), INTERVAL ? HOUR) "
                + "WHERE id = 1 AND state IN ('PENDING', 'EXPIRED') "
                + "AND NOT EXISTS (SELECT 1 FROM app_user WHERE global_role = 'ADMIN')", tokenHashHex, ttlHours);
    }

    @Override
    public boolean insertMerchant(String id, String code, String name, String currency) {
        if (!absent("SELECT 1 FROM merchant WHERE id = ? OR code = ?", id, code)) {
            return false;
        }
        jdbc.update("INSERT INTO merchant (id, code, name, currency, status, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, 'ACTIVE', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)) "
                + "ON DUPLICATE KEY UPDATE id = id", id, code, name, currency);
        return true;
    }

    @Override
    public boolean insertInvoice(String id, String merchantId, String reference, String description, long amountMinor,
                                 String currency) {
        if (!absent("SELECT 1 FROM invoice WHERE id = ? OR (merchant_id = ? AND reference = ?)", id, merchantId,
                reference)) {
            return false;
        }
        jdbc.update("INSERT INTO invoice (id, merchant_id, reference, description, amount_minor, currency, "
                + "active_payment_id, created_at) VALUES (?, ?, ?, ?, ?, ?, NULL, UTC_TIMESTAMP(6)) "
                + "ON DUPLICATE KEY UPDATE id = id", id, merchantId, reference, description, amountMinor, currency);
        return true;
    }

    @Override
    public boolean insertMerchantMethod(String merchantId, String methodCode) {
        if (!absent("SELECT 1 FROM merchant_method WHERE merchant_id = ? AND method_code = ?", merchantId,
                methodCode)) {
            return false;
        }
        jdbc.update("INSERT INTO merchant_method (merchant_id, method_code, enabled) VALUES (?, ?, 1) "
                + "ON DUPLICATE KEY UPDATE merchant_id = merchant_id", merchantId, methodCode);
        return true;
    }

    @Override
    public boolean insertMerchantQuota(String merchantId, int ratePerSecond, int burstCapacity) {
        if (!absent("SELECT 1 FROM merchant_quota_setting WHERE merchant_id = ?", merchantId)) {
            return false;
        }
        return jdbc.update("INSERT INTO merchant_quota_setting (merchant_id, rate_per_second, burst_capacity, "
                + "updated_at) VALUES (?, ?, ?, UTC_TIMESTAMP(6)) ON DUPLICATE KEY UPDATE merchant_id = merchant_id",
                merchantId, ratePerSecond, burstCapacity) == 1;
    }

    @Override
    public boolean insertMembership(String id, String userId, String merchantId, String role) {
        if (!absent("SELECT 1 FROM membership WHERE id = ? OR (user_id = ? AND merchant_id = ?)", id, userId,
                merchantId)) {
            return false;
        }
        jdbc.update("INSERT INTO membership (id, user_id, merchant_id, role, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)) ON DUPLICATE KEY UPDATE id = id",
                id, userId, merchantId, role);
        return true;
    }

    @Override
    public boolean userExists(String username, String id) {
        return !absent("SELECT 1 FROM app_user WHERE username = ? OR id = ?", username, id);
    }
}
