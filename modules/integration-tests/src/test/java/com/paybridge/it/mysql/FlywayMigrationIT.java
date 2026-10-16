package com.paybridge.it.mysql;

import static org.assertj.core.api.Assertions.assertThat;

import com.paybridge.adapter.mysql.migration.SchemaVersion;
import com.paybridge.it.support.MysqlSupport;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

@Tag("integration")
class FlywayMigrationIT {

    @BeforeEach
    void emptySchema() {
        MysqlSupport.resetSchema();
    }

    @Test
    void migratesFromEmptyAndRecordsEveryScript() throws SQLException {
        try (var ds = (HikariDataSource) MysqlSupport.dataSource(MysqlSupport.MIGRATE_USER)) {
            assertThat(SchemaVersion.check(ds).current()).isFalse();
            assertThat(SchemaVersion.migrate(ds)).isEqualTo(2);

            var jdbc = new JdbcTemplate(ds);
            List<String> history = jdbc.queryForList(
                    "SELECT CONCAT(version, ' ', script, ' success=', success) FROM flyway_schema_history "
                            + "WHERE version IS NOT NULL ORDER BY installed_rank", String.class);
            assertThat(history).containsExactly("001 V001__identity_and_invoices.sql success=1",
                    "002 V002__payments.sql success=1");

            List<String> tables = jdbc.queryForList("SELECT table_name FROM information_schema.tables "
                    + "WHERE table_schema = 'paybridge' AND table_name <> 'flyway_schema_history' ORDER BY 1", String.class);
            assertThat(tables).containsExactlyInAnyOrder("app_user", "merchant", "membership", "bootstrap_state",
                    "invoice", "audit_action", "method_catalog", "method_token", "merchant_method", "payment",
                    "payment_request", "payment_attempt");
        }
    }

    @Test
    void secondRunAppliesNothingAndChecksumsAreStable() {
        try (var ds = (HikariDataSource) MysqlSupport.dataSource(MysqlSupport.MIGRATE_USER)) {
            SchemaVersion.migrate(ds);
            var jdbc = new JdbcTemplate(ds);
            var before = checksums(jdbc);

            assertThat(SchemaVersion.migrate(ds)).isZero();
            assertThat(checksums(jdbc)).isEqualTo(before);
            assertThat(before).hasSize(2).doesNotContainNull();
            var status = SchemaVersion.check(ds);
            assertThat(status.current()).isTrue();
            assertThat(status.applied()).isEqualTo("002");
        }
    }

    @Test
    void identifiersAreAsciiBinaryAndTimestampsAreMicrosecondDatetime() {
        MysqlSupport.migrate();
        try (var ds = (HikariDataSource) MysqlSupport.rootDataSource()) {
            var jdbc = new JdbcTemplate(ds);
            List<String> idColumns = new ArrayList<>();
            jdbc.query("SELECT table_name, column_name, character_set_name, collation_name, column_type "
                    + "FROM information_schema.columns WHERE table_schema = 'paybridge' AND column_name IN "
                    + "('id', 'merchant_id', 'invoice_id', 'payment_id', 'user_id') AND data_type = 'char'",
                    rs -> {
                        String where = rs.getString(1) + "." + rs.getString(2);
                        if (!"ascii".equals(rs.getString(3)) || !"ascii_bin".equals(rs.getString(4))
                                || !"char(36)".equals(rs.getString(5))) {
                            idColumns.add(where);
                        }
                    });
            assertThat(idColumns).as("id columns that are not CHAR(36) ascii_bin").isEmpty();

            List<String> wrongTimes = jdbc.queryForList("SELECT CONCAT(table_name, '.', column_name) FROM "
                    + "information_schema.columns WHERE table_schema = 'paybridge' AND column_name LIKE '%\\_at' "
                    + "AND (data_type <> 'datetime' OR datetime_precision <> 6)", String.class);
            assertThat(wrongTimes).isEmpty();

            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = "
                    + "'paybridge' AND engine <> 'InnoDB' AND table_type = 'BASE TABLE'", Integer.class)).isZero();
        }
    }

    private static List<Integer> checksums(JdbcTemplate jdbc) {
        return jdbc.queryForList("SELECT checksum FROM flyway_schema_history WHERE version IS NOT NULL "
                + "ORDER BY installed_rank", Integer.class);
    }
}
