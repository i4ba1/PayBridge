package com.paybridge.adapter.mysql;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paybridge.application.port.AuditStore;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes audit_action inside the caller's transaction (MANDATORY), so a mutation and its audit row cannot diverge.
 * Details are refused when a key looks like a credential: the audit trail must never hold one (FR-063).
 */
@Repository
public class MysqlAuditStore implements AuditStore {
    private static final Set<String> FORBIDDEN_KEY_PARTS = Set.of("password", "credential", "secret", "token", "hash");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcTemplate jdbc;

    MysqlAuditStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(Entry e) {
        String details = null;
        if (e.details() != null && !e.details().isEmpty()) {
            for (String key : e.details().keySet()) {
                String lower = key.toLowerCase(Locale.ROOT);
                if (FORBIDDEN_KEY_PARTS.stream().anyMatch(lower::contains)) {
                    throw new IllegalArgumentException("audit details may not contain the key '" + key + "'");
                }
            }
            try {
                details = JSON.writeValueAsString(e.details());
            } catch (JsonProcessingException ex) {
                throw new IllegalArgumentException("audit details are not serializable");
            }
        }
        jdbc.update("INSERT INTO audit_action (id, occurred_at, actor_type, actor_user_id, merchant_id, action, "
                + "target_type, target_id, result, correlation_id, details) "
                + "VALUES (?, UTC_TIMESTAMP(6), ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS JSON))",
                UUID.randomUUID().toString(), e.actorType().name(), e.actorUserId(), e.merchantId(), e.action(),
                e.targetType(), e.targetId() == null ? null : truncate(e.targetId(), 64), e.result().name(),
                truncate(e.correlationId(), 64), details);
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
