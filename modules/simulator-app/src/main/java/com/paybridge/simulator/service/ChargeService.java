package com.paybridge.simulator.service;

import com.paybridge.simulator.config.SimulatorProperties;
import com.paybridge.simulator.service.ScenarioService.Behavior;
import com.paybridge.simulator.service.ScenarioService.Kind;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Charge creation, lookup and the operator listing (DMR section 9, API contract 8.2 and 8.3).
 *
 * <p>The identity of a charge (key, request hash, scenario run) is committed before any response byte is produced or
 * any delayed event is queued. A repeated identical request returns the stored charge; a changed immutable field is a
 * conflict. Scenario failures that must look like a dead provider are returned as {@link Abort}.
 */
@Service
public class ChargeService {
    private static final SecureRandom RANDOM = new SecureRandom();
    static final int MAX_PAGE = 100;

    /** What the HTTP layer should do. */
    public sealed interface Result permits Respond, Abort {}

    public record Respond(int status, ChargeView charge) implements Result {}

    /** Close the connection with no response, after sleeping (the client's timeout should fire first). */
    public record Abort(long sleepMillis) implements Result {}

    public record Operation(String reference, String providerKey, String paymentId, String scenarioRunId, String status,
                            long revision, String amountMinor, String currency, Instant createdAt, Instant updatedAt) {}

    public record OperationsPage(List<Operation> items, String nextCursor) {}

    private record Stored(String id, String key, String hash, ChargeView view, String paymentId, String scenarioRunId) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ScenarioService scenarios;
    private final SimulatorProperties props;
    private final Clock clock;

    public ChargeService(JdbcTemplate jdbc, TransactionTemplate tx, ScenarioService scenarios, SimulatorProperties props,
                         Clock clock) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.scenarios = scenarios;
        this.props = props;
        this.clock = clock;
    }

    // ---- POST /v1/charges --------------------------------------------------------------------------------------

    public Result charge(ChargeBody charge) {
        String key = "pbk-" + charge.paymentId();
        for (int attempt = 0; ; attempt++) {
            try {
                return tx.execute(status -> handle(key, charge));
            } catch (DuplicateKeyException e) {
                // Two identical requests raced to insert the same charge; the second one now finds the first.
                if (attempt >= 2) {
                    throw e;
                }
            }
        }
    }

    private Result handle(String key, ChargeBody charge) {
        String hash = charge.requestHash();
        Stored existing = find(key).orElse(null);
        if (existing != null) {
            int hits = bump(key, existing.scenarioRunId());
            if (!existing.hash().equals(hash)) {
                throw new ApiException(409, "idempotency_conflict", "The key was used with different request fields");
            }
            Behavior behavior = scenarios.resolve(existing.scenarioRunId(), charge);
            if (behavior.kind() == Kind.SUCCESS_RESPONSE_LOST && hits <= behavior.failFirst()) {
                return new Abort(0);
            }
            return new Respond(200, existing.view());
        }

        String run = charge.scenarioRunId();
        if (!scenarios.usable(run)) {
            // Nothing is persisted, not even a counter row, and the run is never replaced by default.
            throw new ApiException(422, "SCENARIO_NOT_AVAILABLE", "The scenario run is unknown, expired, deactivated or unusable");
        }
        String countedRun = counterRun(key);
        if (countedRun != null && !countedRun.equals(run)) {
            throw new ApiException(409, "idempotency_conflict", "The key was first used with another scenario run");
        }
        int hits = bump(key, run);
        if (hits == 1 && !scenarios.consumeUse(run)) {
            throw new ApiException(422, "SCENARIO_NOT_AVAILABLE", "The scenario run has no uses left");
        }
        Behavior behavior = scenarios.resolve(run, charge);
        Kind kind = behavior.kind();
        if (kind == Kind.TIMEOUT_BEFORE_PROCESSING) {
            if (hits <= behavior.failFirst()) {
                return new Abort(behavior.sleepSeconds() * 1000L);        // counter commits; the charge is never created
            }
            kind = behavior.thenKind() == Kind.DECLINE ? Kind.DECLINE : Kind.SUCCESS;
        }
        ChargeView created = insert(key, hash, charge, behavior, kind);
        if (kind == Kind.SUCCESS_RESPONSE_LOST && hits <= behavior.failFirst()) {
            return new Abort(0);                                          // committed, answer dropped
        }
        // 202 for a pending transfer; a decline is an authoritative answer, not a creation, and is returned as 200 (DMR 9).
        return new Respond("PENDING".equals(created.status()) ? 202 : "DECLINED".equals(created.status()) ? 200 : 201, created);
    }

    private ChargeView insert(String key, String hash, ChargeBody c, Behavior behavior, Kind kind) {
        String id = UUID.randomUUID().toString();
        String reference = "sim_ch_" + HexFormat.of().formatHex(randomBytes(10));
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        boolean delayed = kind == Kind.DELAYED_TRANSFER;
        boolean declined = kind == Kind.DECLINE;
        String status = delayed ? "PENDING" : declined ? "DECLINED" : "SUCCEEDED";
        String plannedFinal = delayed ? ("DECLINED".equals(behavior.finalOutcome()) ? "DECLINED" : "SUCCEEDED") : null;
        // A pending transfer carries no decline code; the completion worker sets it from the run when it finishes.
        String decline = declined ? declineCode(behavior) : null;
        jdbc.update("INSERT INTO sim_charge (id, idempotency_key, request_hash, provider_reference, payment_id, merchant_id, "
                + "invoice_id, amount_minor, currency, method_token, scenario_run_id, status, decline_code, revision, "
                + "planned_final, complete_at, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, "
                + (delayed ? "DATE_ADD(UTC_TIMESTAMP(6), INTERVAL ? SECOND)" : "NULL") + ", ?, ?)",
                args(delayed, id, key, hash, reference, c, status, decline, plannedFinal, behavior.delaySeconds(), now));
        if (!delayed) {
            queueWebhook(id, key, reference, 1, !declined, now, c.amountMinor(), c.currency(), webhookDelay(behavior));
        }
        return new ChargeView(reference, key, status, 1, c.amountMinor(), c.currency(), declined ? decline : null, now, now);
    }

    private static Object[] args(boolean delayed, String id, String key, String hash, String reference, ChargeBody c,
                                 String status, String decline, String plannedFinal, int delaySeconds, Instant now) {
        var list = new ArrayList<Object>(List.of(id, key, hash, reference, c.paymentId(), c.merchantId(), c.invoiceId(),
                Long.parseLong(c.amountMinor()), c.currency(), c.methodToken(), c.scenarioRunId(), status));
        list.add(decline);
        list.add(plannedFinal);
        if (delayed) {
            list.add(delaySeconds);
        }
        list.add(ts(now));
        list.add(ts(now));
        return list.toArray();
    }

    static String declineCode(Behavior b) {
        return b.declineCode() == null ? "INSUFFICIENT_FUNDS" : b.declineCode();
    }

    private long webhookDelay(Behavior b) {
        return b.webhookDelayMs() >= 0 ? b.webhookDelayMs() : props.getWebhook().getDefaultDelayMs();
    }

    void queueWebhook(String chargeId, String key, String reference, long revision, boolean succeeded, Instant observedAt,
                      String amountMinor, String currency, long delayMs) {
        String eventId = UUID.randomUUID().toString();
        String raw = WebhookEvents.body(eventId, key, reference, revision, succeeded, observedAt, amountMinor, currency);
        jdbc.update("INSERT INTO sim_webhook_delivery (id, charge_id, event_id, event_type, revision, body, raw_body, state, "
                + "attempt_count, next_attempt_at, created_at) VALUES (?, ?, ?, ?, ?, CAST(? AS JSON), ?, 'PENDING', 0, "
                + "DATE_ADD(UTC_TIMESTAMP(6), INTERVAL ? MICROSECOND), ?)",
                UUID.randomUUID().toString(), chargeId, eventId, succeeded ? "charge.succeeded" : "charge.declined", revision,
                raw, raw, delayMs * 1000, ts(observedAt));
    }

    // ---- counters ----------------------------------------------------------------------------------------------

    private int bump(String key, String run) {
        jdbc.update("INSERT INTO sim_key_counter (idempotency_key, scenario_run_id, hits, first_seen_at, last_seen_at) "
                + "VALUES (?, ?, 1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)) "
                + "ON DUPLICATE KEY UPDATE hits = hits + 1, last_seen_at = UTC_TIMESTAMP(6)", key, run);
        return jdbc.queryForObject("SELECT hits FROM sim_key_counter WHERE idempotency_key = ?", Integer.class, key);
    }

    private String counterRun(String key) {
        var runs = jdbc.queryForList("SELECT scenario_run_id FROM sim_key_counter WHERE idempotency_key = ?", String.class, key);
        return runs.isEmpty() ? null : runs.get(0);
    }

    // ---- lookup and listing ------------------------------------------------------------------------------------

    public Optional<ChargeView> lookup(String key) {
        return find(key).map(Stored::view);
    }

    private Optional<Stored> find(String key) {
        return jdbc.query("SELECT id, idempotency_key, request_hash, provider_reference, payment_id, scenario_run_id, "
                + "amount_minor, currency, status, decline_code, revision, created_at, updated_at FROM sim_charge "
                + "WHERE idempotency_key = ?", (rs, n) -> new Stored(rs.getString("id"), key, rs.getString("request_hash"),
                view(rs), rs.getString("payment_id"), rs.getString("scenario_run_id")), key).stream().findFirst();
    }

    private static ChargeView view(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ChargeView(rs.getString("provider_reference"), rs.getString("idempotency_key"), rs.getString("status"),
                rs.getLong("revision"), Long.toString(rs.getLong("amount_minor")), rs.getString("currency"),
                rs.getString("decline_code"), instant(rs, "created_at"), instant(rs, "updated_at"));
    }

    public OperationsPage operations(Instant from, Instant to, String status, String cursor, Integer limitParam) {
        int limit = limitParam == null ? 50 : limitParam;
        if (limit < 1 || limit > MAX_PAGE) {
            throw ApiException.invalid("limit must be between 1 and " + MAX_PAGE);
        }
        if (status != null && !List.of("PENDING", "SUCCEEDED", "DECLINED").contains(status)) {
            throw ApiException.invalid("status must be PENDING, SUCCEEDED or DECLINED");
        }
        Instant lo = from == null ? Instant.EPOCH : from;
        Instant hi = to == null ? clock.instant().plus(1, ChronoUnit.DAYS) : to;
        var sql = new StringBuilder("SELECT idempotency_key, provider_reference, payment_id, scenario_run_id, status, revision, "
                + "amount_minor, currency, created_at, updated_at, id FROM sim_charge WHERE updated_at >= ? AND updated_at < ?");
        var params = new ArrayList<Object>(List.of(ts(lo), ts(hi)));
        if (status != null) {
            sql.append(" AND status = ?");
            params.add(status);
        }
        if (cursor != null) {
            String[] c = decodeCursor(cursor);
            sql.append(" AND (updated_at < ? OR (updated_at = ? AND id < ?))");
            LocalDateTime at = ts(Instant.parse(c[0]));
            params.add(at);
            params.add(at);
            params.add(c[1]);
        }
        sql.append(" ORDER BY updated_at DESC, id DESC LIMIT ?");
        params.add(limit + 1);
        RowMapper<Operation> mapper = (rs, n) -> new Operation(rs.getString("provider_reference"), rs.getString("idempotency_key"),
                rs.getString("payment_id"), rs.getString("scenario_run_id"), rs.getString("status"), rs.getLong("revision"),
                Long.toString(rs.getLong("amount_minor")), rs.getString("currency"), instant(rs, "created_at"), instant(rs, "updated_at"));
        var ids = new ArrayList<String>();
        var rows = jdbc.query(sql.toString(), (rs, n) -> {
            ids.add(rs.getString("id"));
            return mapper.mapRow(rs, n);
        }, params.toArray());
        boolean more = rows.size() > limit;
        var page = more ? new ArrayList<>(rows.subList(0, limit)) : rows;
        String next = null;
        if (more) {
            var last = page.get(page.size() - 1);
            next = Base64.getUrlEncoder().withoutPadding().encodeToString((last.updatedAt() + "|" + ids.get(limit - 1))
                    .getBytes(StandardCharsets.UTF_8));
        }
        return new OperationsPage(page, next);
    }

    private static String[] decodeCursor(String cursor) {
        try {
            String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("\\|", 2);
            if (parts.length != 2) {
                throw new IllegalArgumentException();
            }
            Instant.parse(parts[0]);
            return parts;
        } catch (RuntimeException e) {
            throw ApiException.invalid("cursor is not valid");
        }
    }

    // ---- helpers -----------------------------------------------------------------------------------------------

    static Instant instant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        return rs.getObject(column, LocalDateTime.class).toInstant(ZoneOffset.UTC);
    }

    /**
     * A UTC wall clock value. LocalDateTime is written as is, so the result never depends on the JVM time zone or on
     * the connection time zone settings of the JDBC URL.
     */
    static LocalDateTime ts(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        RANDOM.nextBytes(b);
        return b;
    }
}
