package com.paybridge.it.mysql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.paybridge.application.port.IdentityStore;
import com.paybridge.application.port.SeedStore;
import com.paybridge.core.seed.CredentialSink;
import com.paybridge.core.seed.DemoSeeder;
import com.paybridge.core.seed.FileCredentialSink;
import com.paybridge.it.support.Fixtures;
import com.paybridge.it.support.MysqlSpringIT;
import com.paybridge.it.support.MysqlSupport;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;

/** FR-006: seeding twice preserves payments and invoice identities and creates no duplicate rows. */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SeedIdempotencyIT extends MysqlSpringIT {
    private static final DemoSeeder.Options DEMO = new DemoSeeder.Options(true, null, 24);
    private static final List<String> TABLES = List.of("method_catalog", "method_token", "bootstrap_state", "merchant",
            "merchant_method", "merchant_quota_setting", "invoice", "app_user", "membership", "payment", "payment_request",
            "audit_action");

    private static HikariDataSource root;
    private static JdbcTemplate rootJdbc;

    @Autowired SeedStore seedStore;
    @Autowired IdentityStore identityStore;
    @TempDir static Path tmp;

    private final Map<String, String> sinkCalls = new HashMap<>();

    @BeforeAll
    static void openRoot() {
        root = (HikariDataSource) MysqlSupport.rootDataSource();
        rootJdbc = new JdbcTemplate(root);
    }

    @AfterAll
    static void closeRoot() {
        root.close();
    }

    private DemoSeeder seeder(Path file) {
        CredentialSink recording = passwords -> {
            sinkCalls.putAll(passwords);
            new FileCredentialSink(file).store(passwords);
        };
        return new DemoSeeder(seedStore, identityStore, PasswordEncoderFactories.createDelegatingPasswordEncoder(),
                recording);
    }

    /** Empties every table in place; dropping the schema would break the connections the context already holds. */
    private static void clearData() {
        rootJdbc.execute((java.sql.Connection con) -> {
            try (var st = con.createStatement()) {
                st.execute("SET FOREIGN_KEY_CHECKS = 0");
                for (String t : List.of("payment_attempt", "payment_request", "payment", "invoice", "membership",
                        "merchant_method", "merchant_quota_setting", "bootstrap_state", "app_user", "merchant", "method_token", "method_catalog",
                        "audit_action")) {
                    st.execute("DELETE FROM " + t);
                }
                st.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
            return null;
        });
    }

    /** Evidence for FR-006: the row counts around the second seed, written next to the test report. */
    private static void writeEvidence(Map<String, Integer> before, Map<String, Integer> after) throws Exception {
        var out = new StringBuilder(String.format("%-18s %8s %8s%n", "table", "before", "after"));
        for (String t : TABLES) {
            out.append(String.format("%-18s %8d %8d%n", t, before.get(t), after.get(t)));
        }
        Files.createDirectories(Path.of("build"));
        Files.writeString(Path.of("build", "seed-rowcounts.txt"), out.toString());
    }

    private Map<String, Integer> rowCounts() {
        var counts = new HashMap<String, Integer>();
        for (String t : TABLES) {
            counts.put(t, rootJdbc.queryForObject("SELECT COUNT(*) FROM " + t, Integer.class));
        }
        return counts;
    }

    @Test
    @Order(1)
    void firstSeedCreatesTheDocumentedRows() {
        var report = seeder(tmp.resolve("creds.env")).run(DEMO);

        assertThat(report.lockObtained()).isTrue();
        var counts = rowCounts();
        assertThat(counts).containsEntry("method_catalog", 2).containsEntry("method_token", 3)
                .containsEntry("bootstrap_state", 1).containsEntry("merchant", 2).containsEntry("merchant_method", 4)
                .containsEntry("merchant_quota_setting", 2)
                .containsEntry("invoice", 10).containsEntry("app_user", 4).containsEntry("membership", 4)
                .containsEntry("payment", 0);
        assertThat(sinkCalls).containsOnlyKeys("demo-operator-a", "demo-viewer-a", "demo-operator-b", "demo-viewer-b");
        assertThat(sinkCalls.values()).allSatisfy(p -> assertThat(p).hasSize(20).matches("[A-Za-z0-9]{20}"));
        assertThat(rootJdbc.queryForObject("SELECT password_hash FROM app_user WHERE username = 'demo-operator-a'",
                String.class)).startsWith("{bcrypt}").doesNotContain(sinkCalls.get("demo-operator-a"));
        assertThat(rootJdbc.queryForObject("SELECT currency FROM merchant WHERE code = 'harbor-coffee'", String.class))
                .isEqualTo("USD");
        assertThat(rootJdbc.queryForObject("SELECT amount_minor FROM invoice WHERE reference = 'INV-B-004'",
                Long.class)).isEqualTo(75_000L);
        assertThat(rootJdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE global_role = 'ADMIN'", Integer.class))
                .as("administrators are never seeded").isZero();
    }

    @Test
    @Order(2)
    void secondSeedChangesNothingEvenWithPaymentsPresent() throws Exception {
        // Simulate life after the first seed: a payment exists and holds the invoice.
        String invoice = "00000000-0000-4000-8000-000000000401";
        String merchant = "00000000-0000-4000-8000-000000000301";
        String user = "00000000-0000-4000-8000-000000000501";
        String payment = Fixtures.payment(rootJdbc, merchant, invoice, user, "PROCESSING");
        rootJdbc.update("UPDATE invoice SET active_payment_id = ? WHERE id = ?", payment, invoice);
        rootJdbc.update("UPDATE merchant SET name = 'Renamed by admin' WHERE id = ?", merchant);

        var before = rowCounts();
        var paymentRow = rootJdbc.queryForMap("SELECT * FROM payment WHERE id = ?", payment);
        var invoiceRows = rootJdbc.queryForList("SELECT * FROM invoice ORDER BY id");
        var userRows = rootJdbc.queryForList("SELECT id, username, password_hash, version FROM app_user ORDER BY id");
        sinkCalls.clear();
        Path secondFile = tmp.resolve("second.env");

        var report = seeder(secondFile).run(DEMO);

        assertThat(report.lockObtained()).isTrue();
        assertThat(report.tables().values()).allSatisfy(c -> assertThat(c.inserted()).isZero());
        var after = rowCounts();
        writeEvidence(before, after);
        assertThat(after).isEqualTo(before);
        assertThat(rootJdbc.queryForMap("SELECT * FROM payment WHERE id = ?", payment)).isEqualTo(paymentRow);
        assertThat(rootJdbc.queryForList("SELECT * FROM invoice ORDER BY id")).isEqualTo(invoiceRows);
        assertThat(rootJdbc.queryForList("SELECT id, username, password_hash, version FROM app_user ORDER BY id"))
                .as("existing accounts keep their password").isEqualTo(userRows);
        assertThat(rootJdbc.queryForObject("SELECT name FROM merchant WHERE id = ?", String.class, merchant))
                .as("the seed never overwrites an existing row").isEqualTo("Renamed by admin");
        assertThat(sinkCalls).as("no password is generated for existing accounts").isEmpty();
        assertThat(Files.exists(secondFile)).isFalse();
    }

    @Test
    @Order(3)
    void demoMerchantsGetTheDefaultQuotaOnce() {
        var rows = rootJdbc.queryForList("SELECT rate_per_second, burst_capacity FROM merchant_quota_setting");
        assertThat(rows).hasSize(2).allSatisfy(r -> {
            assertThat(r.get("rate_per_second")).isEqualTo(20);
            assertThat(r.get("burst_capacity")).isEqualTo(40);
        });
    }

    @Test
    @Order(4)
    void firstSeedWritesTheCredentialFileWithAllFourAccounts() throws Exception {
        Path file = tmp.resolve("creds.env");
        List<String> lines = Files.readAllLines(file);
        assertThat(lines).hasSize(4).allMatch(l -> l.matches("demo-(operator|viewer)-[ab]=[A-Za-z0-9]{20}"));
    }

    @Test
    @Order(5)
    void baseProfileAddsNoDemoRows() {
        clearData();
        var report = seeder(tmp.resolve("unused.env")).run(new DemoSeeder.Options(false, null, 24));
        assertThat(report.inserted("method_catalog")).isEqualTo(2);
        var counts = rowCounts();
        assertThat(counts).containsEntry("merchant", 0).containsEntry("invoice", 0).containsEntry("app_user", 0);
    }

    @Test
    @Order(6)
    void bootstrapTokenIsHashedRearmedAndIgnoredOnceAnAdministratorExists() {
        var withToken = new DemoSeeder.Options(false, "token-value-for-test", 24);
        var report = seeder(tmp.resolve("unused.env")).run(withToken);
        assertThat(report.bootstrapRearmed()).isOne();

        var row = rootJdbc.queryForMap("SELECT state, token_hash, token_expires_at FROM bootstrap_state WHERE id = 1");
        assertThat(row.get("state")).isEqualTo("PENDING");
        assertThat((String) row.get("token_hash")).matches("[0-9a-f]{64}").isNotEqualTo("token-value-for-test");
        assertThat(row.get("token_expires_at")).isNotNull();

        identityStore.create(new IdentityStore.NewUser(Fixtures.uuid(), "first-admin", "First Admin", "{noop}x",
                IdentityStore.GlobalRole.ADMIN, false));
        assertThat(seeder(tmp.resolve("unused.env")).run(withToken).bootstrapRearmed()).isZero();
    }

    @Test
    @Order(7)
    void aSecondSeedHoldingTheLockIsSkippedWithoutError() throws Exception {
        var holder = (HikariDataSource) MysqlSupport.rootDataSource();
        try (var con = holder.getConnection(); var st = con.createStatement()) {
            st.execute("SELECT GET_LOCK('pb.seed', 5)");
            var seeder = new DemoSeeder(new LockWaitOne(seedStore), identityStore,
                    PasswordEncoderFactories.createDelegatingPasswordEncoder(), p -> { });
            var report = seeder.run(DEMO);
            assertThat(report.lockObtained()).isFalse();
            st.execute("SELECT RELEASE_LOCK('pb.seed')");
        } finally {
            holder.close();
        }
    }

    @Test
    @Order(8)
    void aFailingCredentialSinkCreatesNoAccounts() {
        clearData();
        var failing = new DemoSeeder(seedStore, identityStore, PasswordEncoderFactories.createDelegatingPasswordEncoder(),
                p -> { throw new IllegalStateException("sink down"); });
        assertThatThrownBy(() -> failing.run(DEMO)).hasMessageContaining("sink down");
        assertThat(rootJdbc.queryForObject("SELECT COUNT(*) FROM app_user", Integer.class)).isZero();
    }

    /** Shortens the 30 second lock wait so the busy case does not slow the suite down. */
    private record LockWaitOne(SeedStore delegate) implements SeedStore {
        @Override public <T> java.util.Optional<T> withLock(String name, int t, java.util.function.Supplier<T> w) {
            return delegate.withLock(name, 1, w);
        }
        @Override public <T> T inTransaction(java.util.function.Supplier<T> w) { return delegate.inTransaction(w); }
        @Override public boolean insertMethod(String a, String b, String c) { return delegate.insertMethod(a, b, c); }
        @Override public boolean insertMethodToken(String a, String b, String c) { return delegate.insertMethodToken(a, b, c); }
        @Override public boolean ensureBootstrapRow() { return delegate.ensureBootstrapRow(); }
        @Override public int rearmBootstrapToken(String h, int t) { return delegate.rearmBootstrapToken(h, t); }
        @Override public boolean insertMerchant(String a, String b, String c, String d) { return delegate.insertMerchant(a, b, c, d); }
        @Override public boolean insertInvoice(String a, String b, String c, String d, long e, String f) { return delegate.insertInvoice(a, b, c, d, e, f); }
        @Override public boolean insertMerchantMethod(String a, String b) { return delegate.insertMerchantMethod(a, b); }
        @Override public boolean insertMerchantQuota(String a, int b, int c) { return delegate.insertMerchantQuota(a, b, c); }
        @Override public boolean insertMembership(String a, String b, String c, String d) { return delegate.insertMembership(a, b, c, d); }
        @Override public boolean userExists(String a, String b) { return delegate.userExists(a, b); }
    }
}
