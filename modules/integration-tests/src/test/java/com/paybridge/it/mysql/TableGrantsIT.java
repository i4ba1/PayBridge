package com.paybridge.it.mysql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.paybridge.adapter.mysql.migration.TableGrants;
import com.paybridge.it.support.Fixtures;
import com.paybridge.it.support.MysqlSupport;
import com.zaxxer.hikari.HikariDataSource;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** The grant listing is the contract between the migrations and the least privilege accounts. */
@Tag("integration")
class TableGrantsIT {
    private static HikariDataSource root;
    private static HikariDataSource core;
    private static HikariDataSource reader;
    private static JdbcTemplate rootJdbc;

    @BeforeAll
    static void schema() {
        MysqlSupport.freshlyMigratedSchema();
        root = (HikariDataSource) MysqlSupport.rootDataSource();
        core = (HikariDataSource) MysqlSupport.dataSource(MysqlSupport.CORE_USER);
        reader = (HikariDataSource) MysqlSupport.dataSource(MysqlSupport.READER_USER);
        rootJdbc = new JdbcTemplate(root);
    }

    @AfterAll
    static void close() {
        root.close();
        core.close();
        reader.close();
    }

    @Test
    void everyTableHasAnExplicitCoreGrant() {
        List<String> tables = rootJdbc.queryForList("SELECT table_name FROM information_schema.tables WHERE "
                + "table_schema = 'paybridge' AND table_type = 'BASE TABLE'", String.class);
        List<String> granted = rootJdbc.queryForList("SELECT table_name FROM mysql.tables_priv WHERE "
                + "db = 'paybridge' AND user = 'pb_core'", String.class);
        assertThat(granted).as("a new migration must extend db/grants/table-grants.sql").containsAll(tables);
    }

    @Test
    void applyingTheGrantsTwiceIsHarmless() {
        try (var migrate = (HikariDataSource) MysqlSupport.dataSource(MysqlSupport.MIGRATE_USER)) {
            int first = TableGrants.apply(migrate);
            assertThat(TableGrants.apply(migrate)).isEqualTo(first).isPositive();
        }
    }

    @Test
    void coreCanDoDmlButNothingMoreOnTheEventualTables() {
        var jdbc = new JdbcTemplate(core);
        String merchant = Fixtures.merchant(rootJdbc, "IDR");
        String user = Fixtures.user(rootJdbc);
        String invoice = Fixtures.invoice(rootJdbc, merchant, 100, "IDR");
        String payment = Fixtures.payment(rootJdbc, merchant, invoice, user, "REQUESTED");

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment WHERE id = ?", Integer.class, payment)).isOne();
        jdbc.update("UPDATE payment SET status = 'PROCESSING' WHERE id = ?", payment);
        jdbc.update("INSERT INTO audit_action (id, occurred_at, actor_type, action, target_type, result, "
                + "correlation_id) VALUES (?, UTC_TIMESTAMP(6), 'SYSTEM', 'TEST', 'payment', 'SUCCESS', 'c1')",
                Fixtures.uuid());

        assertThatThrownBy(() -> jdbc.update("DELETE FROM payment WHERE id = ?", payment)).rootCause().hasMessageContaining("denied");
        assertThatThrownBy(() -> jdbc.update("UPDATE audit_action SET result = 'FAILURE'")).rootCause().hasMessageContaining("denied");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM audit_action")).rootCause().hasMessageContaining("denied");
        assertThatThrownBy(() -> jdbc.update("UPDATE payment_request SET request_hash = 'x'"))
                .rootCause().hasMessageContaining("denied");
        assertThatThrownBy(() -> jdbc.execute("CREATE TABLE should_not_exist (id INT)"))
                .rootCause().hasMessageContaining("denied");
        assertThatThrownBy(() -> jdbc.execute("ALTER TABLE payment ADD COLUMN x INT")).rootCause().hasMessageContaining("denied");
        assertThatThrownBy(() -> jdbc.execute("DROP TABLE payment")).rootCause().hasMessageContaining("denied");
    }

    @Test
    void readerOnlyReads() {
        var jdbc = new JdbcTemplate(reader);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM merchant", Integer.class)).isNotNull();
        assertThatThrownBy(() -> jdbc.update("DELETE FROM merchant")).rootCause().hasMessageContaining("denied");
        assertThatThrownBy(() -> jdbc.update("UPDATE merchant SET name = 'x'")).rootCause().hasMessageContaining("denied");
    }
}
