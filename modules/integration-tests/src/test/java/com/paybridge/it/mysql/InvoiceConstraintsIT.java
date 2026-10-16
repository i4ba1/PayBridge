package com.paybridge.it.mysql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.paybridge.adapter.mysql.entity.InvoiceEntity;
import com.paybridge.it.support.Fixtures;
import com.paybridge.it.support.MysqlSupport;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Instant;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/** Invoice and identity constraints at the database level, plus the matching guard in the mapping. */
@Tag("integration")
class InvoiceConstraintsIT {
    private static HikariDataSource root;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void schema() {
        MysqlSupport.freshlyMigratedSchema();
        root = (HikariDataSource) MysqlSupport.rootDataSource();
        jdbc = new JdbcTemplate(root);
    }

    @AfterAll
    static void close() {
        root.close();
    }

    @Test
    void databaseRejectsNonPositiveAmounts() {
        String merchant = Fixtures.merchant(jdbc, "IDR");
        assertThatThrownBy(() -> Fixtures.invoice(jdbc, merchant, 0, "IDR"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_invoice_amount");
        assertThatThrownBy(() -> Fixtures.invoice(jdbc, merchant, -5, "IDR"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_invoice_amount");
        assertThat(Fixtures.invoice(jdbc, merchant, 1, "IDR")).isNotBlank();
    }

    @Test
    void databaseRejectsUnknownCurrencies() {
        String merchant = Fixtures.merchant(jdbc, "USD");
        assertThatThrownBy(() -> Fixtures.invoice(jdbc, merchant, 100, "EUR"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_invoice_currency");
        assertThatThrownBy(() -> Fixtures.merchant(jdbc, "GBP"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_merchant_currency");
    }

    @Test
    void mappingRejectsWhatTheDatabaseRejects() {
        assertThatThrownBy(() -> new InvoiceEntity("i", "m", "INV-1", "", 0, "IDR", Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InvoiceEntity("i", "m", "INV-1", "", 10, "EUR", Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void referenceIsUniquePerMerchantOnly() {
        String a = Fixtures.merchant(jdbc, "IDR");
        String b = Fixtures.merchant(jdbc, "IDR");
        jdbc.update("INSERT INTO invoice (id, merchant_id, reference, amount_minor, currency, created_at) "
                + "VALUES (?, ?, 'INV-X', 10, 'IDR', UTC_TIMESTAMP(6))", Fixtures.uuid(), a);
        jdbc.update("INSERT INTO invoice (id, merchant_id, reference, amount_minor, currency, created_at) "
                + "VALUES (?, ?, 'INV-X', 10, 'IDR', UTC_TIMESTAMP(6))", Fixtures.uuid(), b);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO invoice (id, merchant_id, reference, amount_minor, "
                + "currency, created_at) VALUES (?, ?, 'INV-X', 10, 'IDR', UTC_TIMESTAMP(6))", Fixtures.uuid(), a))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ux_invoice_merchant_reference");
    }

    @Test
    void invoiceNeedsAnExistingMerchantAndAnExistingActivePayment() {
        assertThatThrownBy(() -> Fixtures.invoice(jdbc, Fixtures.uuid(), 10, "IDR"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("fk_invoice_merchant");
        String merchant = Fixtures.merchant(jdbc, "IDR");
        String invoice = Fixtures.invoice(jdbc, merchant, 10, "IDR");
        assertThatThrownBy(() -> jdbc.update("UPDATE invoice SET active_payment_id = ? WHERE id = ?",
                Fixtures.uuid(), invoice))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("fk_invoice_active_payment");
    }

    @Test
    void usernamesAreUniqueAndFollowThePattern() {
        String user = Fixtures.user(jdbc);
        String username = jdbc.queryForObject("SELECT username FROM app_user WHERE id = ?", String.class, user);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO app_user (id, username, display_name, password_hash, "
                + "created_at, updated_at) VALUES (?, ?, 'x', 'x', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))",
                Fixtures.uuid(), username))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ux_app_user_username");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO app_user (id, username, display_name, password_hash, "
                + "created_at, updated_at) VALUES (?, 'Bad Name', 'x', 'x', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))",
                Fixtures.uuid()))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_app_user_username");
    }

    @Test
    void membershipIsUniquePerUserAndMerchant() {
        String user = Fixtures.user(jdbc);
        String merchant = Fixtures.merchant(jdbc, "IDR");
        String sql = "INSERT INTO membership (id, user_id, merchant_id, role, created_at, updated_at) "
                + "VALUES (?, ?, ?, 'OPERATOR', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))";
        jdbc.update(sql, Fixtures.uuid(), user, merchant);
        assertThatThrownBy(() -> jdbc.update(sql, Fixtures.uuid(), user, merchant))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ux_membership_user_merchant");
    }

    @Test
    void bootstrapStateIsASingleton() {
        jdbc.update("INSERT INTO bootstrap_state (id, state, created_at) VALUES (1, 'PENDING', UTC_TIMESTAMP(6))");
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO bootstrap_state (id, state, created_at) VALUES (2, 'PENDING', UTC_TIMESTAMP(6))"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_bootstrap_singleton");
    }
}
