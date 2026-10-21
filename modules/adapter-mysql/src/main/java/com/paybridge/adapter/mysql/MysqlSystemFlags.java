package com.paybridge.adapter.mysql;

import com.paybridge.application.port.SystemFlags;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Reads admission.capacity and admission.maintenance, cached for paybridge.admission.flag-cache-ms (default 1000). */
@Repository
public class MysqlSystemFlags implements SystemFlags {
    private record Snapshot(boolean maintenanceOn, boolean capacityClosed, long loadedAtMillis) {}

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final long cacheMillis;
    private volatile Snapshot snapshot;

    MysqlSystemFlags(JdbcTemplate jdbc, Clock clock, @Value("${paybridge.admission.flag-cache-ms:1000}") long cacheMillis) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.cacheMillis = cacheMillis;
    }

    @Override
    public boolean maintenanceOn() {
        return current().maintenanceOn();
    }

    @Override
    public boolean capacityClosed() {
        return current().capacityClosed();
    }

    private Snapshot current() {
        Snapshot s = snapshot;
        long now = clock.millis();
        if (s != null && now - s.loadedAtMillis() < cacheMillis) {
            return s;
        }
        boolean[] values = new boolean[2];
        jdbc.query("SELECT flag_key, flag_value FROM system_flag WHERE flag_key IN ('admission.capacity', 'admission.maintenance')",
                rs -> {
                    if ("admission.maintenance".equals(rs.getString(1))) {
                        values[0] = "ON".equals(rs.getString(2));
                    } else {
                        values[1] = "CLOSED".equals(rs.getString(2));
                    }
                });
        snapshot = new Snapshot(values[0], values[1], now);
        return snapshot;
    }
}
