package com.paybridge.it.mysql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.paybridge.core.CoreApplication;
import com.paybridge.it.support.MysqlSupport;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The real composition root started as the migrate and seed Jobs start it: roles from PAYBRIDGE_ROLES, database
 * credentials from the writer settings, exit code zero on success and a failure otherwise.
 */
@Tag("integration")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MigrateAndSeedRoleIT {
    @TempDir static Path tmp;

    private static ConfigurableApplicationContext start(String role, String user, String password, String... extra) {
        var props = new java.util.ArrayList<String>(java.util.List.of(
                "--paybridge.roles=" + role,
                "--paybridge.datasource.writer.url=" + MysqlSupport.jdbcUrl(),
                "--paybridge.datasource.writer.username=" + user,
                "--paybridge.datasource.writer.password=" + password,
                "--spring.main.banner-mode=off"));
        props.addAll(java.util.List.of(extra));
        return new SpringApplicationBuilder(CoreApplication.class).web(WebApplicationType.NONE)
                .run(props.toArray(String[]::new));
    }

    @Test
    @Order(1)
    void migrateRoleBringsAnEmptyDatabaseUpToDateAndExitsZero() {
        MysqlSupport.resetSchema();
        try (var ctx = start("migrate", MysqlSupport.MIGRATE_USER, MysqlSupport.PASSWORD)) {
            assertThat(SpringApplication.exit(ctx)).isZero();
        }
        try (var ds = (HikariDataSource) MysqlSupport.rootDataSource()) {
            var jdbc = new JdbcTemplate(ds);
            assertThat(jdbc.queryForObject("SELECT MAX(version) FROM flyway_schema_history", String.class))
                    .isEqualTo("002");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM mysql.tables_priv WHERE db = 'paybridge' AND "
                    + "user = 'pb_core'", Integer.class)).isGreaterThan(10);
        }
    }

    @Test
    @Order(2)
    void migrateRoleIsRepeatable() {
        try (var ctx = start("migrate", MysqlSupport.MIGRATE_USER, MysqlSupport.PASSWORD)) {
            assertThat(SpringApplication.exit(ctx)).isZero();
        }
    }

    @Test
    @Order(3)
    void migrateRoleFailsWithAWrongPassword() {
        assertThatThrownBy(() -> start("migrate", MysqlSupport.MIGRATE_USER, "wrong").close()).isNotNull();
    }

    @Test
    @Order(4)
    void seedRoleRunsAsCoreAndWritesCredentialsToTheSink() throws Exception {
        Path creds = tmp.resolve("demo-accounts.env");
        for (int run = 0; run < 2; run++) {
            try (var ctx = start("seed", MysqlSupport.CORE_USER, MysqlSupport.PASSWORD,
                    "--paybridge.seed.profile=demo", "--paybridge.seed.credentials.sink=file",
                    "--paybridge.seed.credentials.file=" + creds.toString().replace('\\', '/'),
                    "--paybridge.seed.bootstrap-token=role-test-token")) {
                assertThat(SpringApplication.exit(ctx)).isZero();
            }
        }
        assertThat(Files.readAllLines(creds)).hasSize(4);
        try (var ds = (HikariDataSource) MysqlSupport.rootDataSource()) {
            var jdbc = new JdbcTemplate(ds);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM invoice", Integer.class)).isEqualTo(10);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM app_user", Integer.class)).isEqualTo(4);
            assertThat(jdbc.queryForObject("SELECT state FROM bootstrap_state WHERE id = 1", String.class))
                    .isEqualTo("PENDING");
            assertThat(jdbc.queryForObject("SELECT token_hash IS NOT NULL FROM bootstrap_state WHERE id = 1",
                    Boolean.class)).isTrue();
        }
    }

    @Test
    @Order(5)
    void anApplicationRoleDoesNotMigrate() {
        MysqlSupport.resetSchema();
        try (var ctx = start("api", MysqlSupport.MIGRATE_USER, MysqlSupport.PASSWORD)) {
            assertThat(ctx.isRunning()).isTrue();
            try (var ds = (HikariDataSource) MysqlSupport.rootDataSource()) {
                assertThat(new JdbcTemplate(ds).queryForObject("SELECT COUNT(*) FROM information_schema.tables "
                        + "WHERE table_schema = 'paybridge'", Integer.class)).isZero();
            }
        }
    }
}
