package com.paybridge.adapter.mysql;

import com.paybridge.application.port.BootstrapStore;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class MysqlBootstrapStore implements BootstrapStore {
    private final JdbcTemplate jdbc;

    MysqlBootstrapStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Row> lock() {
        // The expiry comparison uses the database clock (TIS section 6), not the application clock.
        return jdbc.query("SELECT state, token_hash, (token_expires_at IS NULL OR token_expires_at < UTC_TIMESTAMP(6)) "
                + "AS expired FROM bootstrap_state WHERE id = 1 FOR UPDATE",
                (rs, n) -> new Row(State.valueOf(rs.getString("state")), rs.getString("token_hash"),
                        rs.getBoolean("expired"))).stream().findFirst();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void markExpired() {
        jdbc.update("UPDATE bootstrap_state SET state = 'EXPIRED' WHERE id = 1 AND state = 'PENDING'");
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean consume(String administratorId) {
        return jdbc.update("UPDATE bootstrap_state SET state = 'CONSUMED', consumed_at = UTC_TIMESTAMP(6), "
                + "consumed_by_user_id = ?, token_hash = NULL WHERE id = 1 AND state = 'PENDING'", administratorId) == 1;
    }
}
