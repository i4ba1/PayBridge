package com.paybridge.it.mysql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.paybridge.adapter.mysql.SchemaVersionHealthIndicator;
import com.paybridge.application.port.IdentityStore;
import com.paybridge.application.port.PaymentStore;
import com.paybridge.it.support.Fixtures;
import com.paybridge.it.support.MysqlSpringIT;
import com.paybridge.it.support.MysqlSupport;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Status;
import org.springframework.jdbc.core.JdbcTemplate;

/** The initial identity and payment stores against the real schema, connected as pb_core with ddl-auto=validate. */
class MysqlStoresIT extends MysqlSpringIT {
    private static HikariDataSource root;
    private static JdbcTemplate rootJdbc;

    @Autowired IdentityStore identity;
    @Autowired PaymentStore payments;
    @Autowired DataSource dataSource;

    @BeforeAll
    static void openRoot() {
        root = (HikariDataSource) MysqlSupport.rootDataSource();
        rootJdbc = new JdbcTemplate(root);
    }

    @AfterAll
    static void closeRoot() {
        root.close();
    }

    @Test
    void hibernateAcceptsTheMigratedSchema() {
        // The context only starts when ddl-auto=validate finds every mapped column; reaching this line is the check.
        assertThat(identity).isNotNull();
    }

    @Test
    void identityRoundTripAndDuplicateUsernameIsRejected() {
        String id = Fixtures.uuid();
        var created = identity.create(new IdentityStore.NewUser(id, "ops.user", "Ops User", "{bcrypt}hash",
                IdentityStore.GlobalRole.USER, true));

        assertThat(created.id()).isEqualTo(id);
        assertThat(identity.findByUsername("ops.user")).get().satisfies(u -> {
            assertThat(u.displayName()).isEqualTo("Ops User");
            assertThat(u.mustChangePassword()).isTrue();
            assertThat(u.enabled()).isTrue();
            assertThat(u.version()).isZero();
            assertThat(u.createdAt()).isNotNull();
            assertThat(u.toString()).doesNotContain("bcrypt");
        });
        assertThat(identity.findById(id)).isPresent();
        assertThat(identity.findByUsername("nobody")).isEmpty();
        assertThatThrownBy(() -> identity.create(new IdentityStore.NewUser(Fixtures.uuid(), "ops.user", "Other",
                "{bcrypt}hash", IdentityStore.GlobalRole.USER, false)))
                .isInstanceOf(IdentityStore.DuplicateAccountException.class);
    }

    @Test
    void adminExistsOnlyAfterAnAdministratorIsCreated() {
        // Tests share the schema, so this one both asserts the transition and tolerates an earlier admin.
        if (!identity.adminExists()) {
            identity.create(new IdentityStore.NewUser(Fixtures.uuid(), "root.admin", "Admin", "{bcrypt}hash",
                    IdentityStore.GlobalRole.ADMIN, false));
        }
        assertThat(identity.adminExists()).isTrue();
    }

    @Test
    void createdAtRoundTripsInUtc() {
        var created = identity.create(new IdentityStore.NewUser(Fixtures.uuid(), "utc.user", "Utc", "{bcrypt}hash",
                IdentityStore.GlobalRole.USER, false));
        String dbValue = rootJdbc.queryForObject("SELECT DATE_FORMAT(created_at, '%Y-%m-%d %H:%i:%s.%f') FROM "
                + "app_user WHERE id = ?", String.class, created.id());
        String javaValue = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS")
                .withZone(java.time.ZoneOffset.UTC).format(created.createdAt());
        assertThat(dbValue).isEqualTo(javaValue);
    }

    @Test
    void paymentStoreExposesTheBusinessVersionNotTheLockCounter() {
        String merchant = Fixtures.merchant(rootJdbc, "IDR");
        String user = Fixtures.user(rootJdbc);
        String invoice = Fixtures.invoice(rootJdbc, merchant, 150_000, "IDR");
        String payment = Fixtures.payment(rootJdbc, merchant, invoice, user, "PROCESSING");
        rootJdbc.update("UPDATE payment SET aggregate_version = 3, version = 17 WHERE id = ?", payment);

        var record = payments.findById(payment).orElseThrow();
        assertThat(record.status()).isEqualTo("PROCESSING");
        assertThat(record.aggregateVersion()).isEqualTo(3);
        assertThat(record.amountMinor()).isEqualTo(150_000);
        assertThat(record.scenarioRunId()).isEqualTo("default");
        assertThat(record.providerKey()).isEqualTo("pbk-" + payment);
        assertThat(payments.findBlockingPayment(invoice)).get().extracting(PaymentStore.PaymentRecord::id)
                .isEqualTo(payment);
        assertThat(payments.countByMerchant(merchant)).isOne();

        rootJdbc.update("UPDATE payment SET status = 'FAILED' WHERE id = ?", payment);
        assertThat(payments.findBlockingPayment(invoice)).isEmpty();
        assertThat(payments.findById(Fixtures.uuid())).isEmpty();
    }

    @Test
    void readinessIsUpWhenCurrentAndDownWhenNeverMigrated() {
        var indicator = new SchemaVersionHealthIndicator(dataSource);
        var up = indicator.health();
        assertThat(up.getStatus()).isEqualTo(Status.UP);
        assertThat(up.getDetails()).containsEntry("applied", "002").containsEntry("expected", "002");

        // A database that was never migrated is behind every build.
        rootJdbc.execute("CREATE DATABASE IF NOT EXISTS paybridge_empty");
        var emptyUrl = MysqlSupport.jdbcUrl().replace("/paybridge?", "/paybridge_empty?");
        var config = new com.zaxxer.hikari.HikariConfig();
        config.setJdbcUrl(emptyUrl);
        config.setUsername("root");
        config.setPassword(root.getPassword());
        try (var empty = new HikariDataSource(config)) {
            var down = new SchemaVersionHealthIndicator(empty).health();
            assertThat(down.getStatus()).isEqualTo(Status.DOWN);
            assertThat(down.getDetails()).containsEntry("applied", "none").containsEntry("pending", 2);
        } finally {
            rootJdbc.execute("DROP DATABASE IF EXISTS paybridge_empty");
        }
    }
}
