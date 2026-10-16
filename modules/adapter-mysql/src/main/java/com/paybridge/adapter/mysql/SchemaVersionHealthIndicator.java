package com.paybridge.adapter.mysql;

import com.paybridge.adapter.mysql.migration.SchemaVersion;
import javax.sql.DataSource;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

/** Reports DOWN while the database schema is behind the build, so readiness fails until migrate has run. */
public class SchemaVersionHealthIndicator implements HealthIndicator {
    private final DataSource dataSource;

    public SchemaVersionHealthIndicator(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public Health health() {
        try {
            var s = SchemaVersion.check(dataSource);
            var builder = s.current() ? Health.up() : Health.down();
            return builder.withDetail("expected", s.expected()).withDetail("applied", s.applied())
                    .withDetail("pending", s.pending()).build();
        } catch (RuntimeException e) {
            return Health.down().withDetail("error", e.getClass().getSimpleName()).build();
        }
    }
}
