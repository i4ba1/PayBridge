package com.paybridge.adapter.mysql.migration;

import java.util.Arrays;
import java.util.Comparator;
import java.util.Optional;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;

/**
 * Compares the migrations packaged with this build against what the database has applied. Application roles use it to
 * refuse readiness while the schema is behind; the migrate role uses {@link #migrate} to bring it up to date.
 */
public final class SchemaVersion {
    public static final String LOCATION = "classpath:db/migration";

    public record Status(String expected, String applied, boolean current, int pending) {}

    private SchemaVersion() {}

    private static Flyway flyway(DataSource dataSource) {
        return Flyway.configure().dataSource(dataSource).locations(LOCATION).validateOnMigrate(true).load();
    }

    /** Runs pending migrations with the DDL account's data source. Returns the number applied. */
    public static int migrate(DataSource ddlDataSource) {
        return flyway(ddlDataSource).migrate().migrationsExecuted;
    }

    public static Status check(DataSource dataSource) {
        var info = flyway(dataSource).info();
        Optional<MigrationVersion> expected = Arrays.stream(info.all()).map(MigrationInfo::getVersion)
                .filter(v -> v != null).max(Comparator.naturalOrder());
        MigrationVersion applied = info.current() == null ? null : info.current().getVersion();
        int pending = info.pending().length;
        boolean current = pending == 0 && applied != null && expected.isPresent() && applied.equals(expected.get());
        return new Status(expected.map(MigrationVersion::getVersion).orElse("none"),
                applied == null ? "none" : applied.getVersion(), current, pending);
    }
}
