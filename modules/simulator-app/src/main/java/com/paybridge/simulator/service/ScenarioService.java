package com.paybridge.simulator.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paybridge.simulator.config.SimulatorProperties;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Scenario runs, their single rule, and the evaluation of a charge request against a run (DMR section 9). */
@Service
public class ScenarioService {
    public static final String DEFAULT_RUN = "default";
    static final Set<String> DECLINE_CODES = Set.of("INSUFFICIENT_FUNDS", "CARD_DECLINED", "TRANSFER_REJECTED",
            "SUSPECTED_FRAUD", "UNSPECIFIED");
    private static final ObjectMapper JSON = new ObjectMapper();

    public enum Kind { SUCCESS, DECLINE, DELAYED_TRANSFER, TIMEOUT_BEFORE_PROCESSING, SUCCESS_RESPONSE_LOST }

    public enum State { ACTIVE, EXPIRED, DEACTIVATED, EXHAUSTED }

    /** The behavior a charge request resolved to. webhookDelayMs < 0 means the configured default. */
    public record Behavior(Kind kind, int delaySeconds, String finalOutcome, String declineCode, int failFirst,
                           int sleepSeconds, long webhookDelayMs, Kind thenKind) {}

    public record Run(String runId, String description, State state, Kind scenario, Integer delaySeconds, Integer maxUses,
                      int usedCount, Instant createdAt, Instant expiresAt) {}

    /** Body of POST /internal/scenario-runs. Unknown properties are rejected by the JSON mapper. */
    public record CreateRun(String runId, String scenario, String description, Integer delaySeconds, String finalOutcome,
                            String declineCode, Integer failFirst, Integer sleepSeconds, Long webhookDelayMs,
                            String thenBehavior, Integer maxUses, Instant expiresAt) {}

    private record RunRow(String runId, String description, boolean active, Integer maxUses, int usedCount,
                          Instant createdAt, Instant expiresAt) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final SimulatorProperties props;

    public ScenarioService(JdbcTemplate jdbc, TransactionTemplate tx, SimulatorProperties props) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.props = props;
    }

    // ---- management --------------------------------------------------------------------------------------------

    public Run create(CreateRun body) {
        if (body.runId() == null || !ChargeBody.RUN_ID.matcher(body.runId()).matches()) {
            throw ApiException.invalid("runId must be 1 to 64 letters, digits, dots, underscores or hyphens");
        }
        if (DEFAULT_RUN.equals(body.runId())) {
            throw new ApiException(409, "run_exists", "The default run cannot be edited or replaced");
        }
        Kind kind;
        try {
            kind = Kind.valueOf(String.valueOf(body.scenario()));
        } catch (IllegalArgumentException e) {
            throw ApiException.invalid("scenario must be one of " + List.of(Kind.values()));
        }
        Map<String, Object> params = params(kind, body);
        if (body.maxUses() != null && body.maxUses() < 1) {
            throw ApiException.invalid("maxUses must be at least 1");
        }
        Instant now = Instant.now();
        Instant expires = body.expiresAt() != null ? body.expiresAt() : now.plus(Duration.ofHours(props.getRunTtlHours()));
        if (!expires.isAfter(now) || expires.isAfter(now.plus(Duration.ofHours(Math.max(props.getRunTtlHours(), 1) * 7L)))) {
            throw ApiException.invalid("expiresAt must be in the future and within seven run lifetimes");
        }
        String description = body.description() == null ? "" : body.description();
        if (description.length() > 200) {
            throw ApiException.invalid("description is limited to 200 characters");
        }
        try {
            tx.executeWithoutResult(s -> {
                jdbc.update("INSERT INTO sim_scenario_run (run_id, description, active, max_uses, used_count, created_by, "
                        + "created_at, expires_at) VALUES (?, ?, 1, ?, 0, 'simulator-admin', UTC_TIMESTAMP(6), ?)",
                        body.runId(), description, body.maxUses(), LocalDateTime.ofInstant(expires, ZoneOffset.UTC));
                jdbc.update("INSERT INTO sim_scenario_rule (run_id, priority, behavior, params) VALUES (?, 1000, ?, CAST(? AS JSON))",
                        body.runId(), kind.name(), json(params));
            });
        } catch (DuplicateKeyException e) {
            throw new ApiException(409, "run_exists", "A run with this id already exists");
        }
        return get(body.runId()).orElseThrow();
    }

    public Optional<Run> get(String runId) {
        return row(runId).map(r -> toRun(r, ruleKind(runId), ruleDelay(runId)));
    }

    /** Idempotent. The default run cannot be deactivated. */
    public Run deactivate(String runId) {
        if (DEFAULT_RUN.equals(runId)) {
            throw new ApiException(409, "run_protected", "The default run cannot be deactivated");
        }
        if (row(runId).isEmpty()) {
            throw new ApiException(404, "run_not_found", "No such scenario run");
        }
        jdbc.update("UPDATE sim_scenario_run SET active = 0 WHERE run_id = ?", runId);
        return get(runId).orElseThrow();
    }

    // ---- evaluation --------------------------------------------------------------------------------------------

    /**
     * The run must exist, be active and not be expired. The default run is always usable. Exhaustion is not checked
     * here: it only restricts new provider keys and is enforced where the key is counted.
     */
    public boolean usable(String runId) {
        return row(runId).filter(r -> r.active() && (r.expiresAt() == null || r.expiresAt().isAfter(Instant.now()))).isPresent();
    }

    public Behavior resolve(String runId, ChargeBody charge) {
        var rules = jdbc.queryForList("SELECT behavior, params, match_method_token, match_currency, match_amount_minor "
                + "FROM sim_scenario_rule WHERE run_id = ? ORDER BY priority ASC", runId);
        for (Map<String, Object> rule : rules) {
            String token = (String) rule.get("match_method_token");
            String currency = (String) rule.get("match_currency");
            Number amount = (Number) rule.get("match_amount_minor");
            boolean matches = (token == null || token.equals(charge.methodToken()))
                    && (currency == null || currency.equals(charge.currency()))
                    && (amount == null || Long.toString(amount.longValue()).equals(charge.amountMinor()));
            if (matches) {
                return behavior(Kind.valueOf((String) rule.get("behavior")), parse(String.valueOf(rule.get("params"))));
            }
        }
        return behavior(Kind.SUCCESS, Map.of());          // no rule matches: success
    }

    private Behavior behavior(Kind kind, Map<String, Object> p) {
        Kind then = Kind.valueOf(String.valueOf(p.getOrDefault("thenBehavior", "SUCCESS")));
        return new Behavior(kind, num(p, "delaySeconds", 3), String.valueOf(p.getOrDefault("finalOutcome", "SUCCEEDED")),
                (String) p.get("declineCode"), num(p, "failFirst", 1), num(p, "sleepSeconds", props.getTimeoutSleepSeconds()),
                ((Number) p.getOrDefault("webhookDelayMs", -1)).longValue(), then);
    }

    private static int num(Map<String, Object> p, String key, int fallback) {
        Object v = p.get(key);
        return v == null ? fallback : ((Number) v).intValue();
    }

    // ---- counting ----------------------------------------------------------------------------------------------

    /** Consumes one use of the run for a new provider key. False when the run is exhausted. */
    public boolean consumeUse(String runId) {
        if (DEFAULT_RUN.equals(runId)) {
            return true;
        }
        return jdbc.update("UPDATE sim_scenario_run SET used_count = used_count + 1 "
                + "WHERE run_id = ? AND (max_uses IS NULL OR used_count < max_uses)", runId) == 1;
    }

    // ---- helpers -----------------------------------------------------------------------------------------------

    private Map<String, Object> params(Kind kind, CreateRun b) {
        var p = new LinkedHashMap<String, Object>();
        Set<String> allowed = switch (kind) {
            case SUCCESS -> Set.of("webhookDelayMs");
            case DECLINE -> Set.of("declineCode", "webhookDelayMs");
            case DELAYED_TRANSFER -> Set.of("delaySeconds", "finalOutcome", "declineCode", "webhookDelayMs");
            case TIMEOUT_BEFORE_PROCESSING -> Set.of("failFirst", "sleepSeconds", "thenBehavior");
            case SUCCESS_RESPONSE_LOST -> Set.of("failFirst", "webhookDelayMs");
        };
        var given = new LinkedHashMap<String, Object>();
        given.put("delaySeconds", b.delaySeconds());
        given.put("finalOutcome", b.finalOutcome());
        given.put("declineCode", b.declineCode());
        given.put("failFirst", b.failFirst());
        given.put("sleepSeconds", b.sleepSeconds());
        given.put("webhookDelayMs", b.webhookDelayMs());
        given.put("thenBehavior", b.thenBehavior());
        given.forEach((k, v) -> {
            if (v == null) {
                return;
            }
            if (!allowed.contains(k)) {
                throw ApiException.invalid(k + " does not apply to " + kind);
            }
            p.put(k, v);
        });
        range(b.delaySeconds(), 0, 3600, "delaySeconds");
        range(b.failFirst(), 1, 20, "failFirst");
        range(b.sleepSeconds(), 1, 120, "sleepSeconds");
        if (b.webhookDelayMs() != null && (b.webhookDelayMs() < 0 || b.webhookDelayMs() > 600_000)) {
            throw ApiException.invalid("webhookDelayMs must be between 0 and 600000");
        }
        if (b.declineCode() != null && !DECLINE_CODES.contains(b.declineCode())) {
            throw ApiException.invalid("declineCode must be one of " + DECLINE_CODES);
        }
        if (b.finalOutcome() != null && !Set.of("SUCCEEDED", "DECLINED").contains(b.finalOutcome())) {
            throw ApiException.invalid("finalOutcome must be SUCCEEDED or DECLINED");
        }
        if (b.thenBehavior() != null && !Set.of("SUCCESS", "DECLINE").contains(b.thenBehavior())) {
            throw ApiException.invalid("thenBehavior must be SUCCESS or DECLINE");
        }
        if (kind == Kind.DELAYED_TRANSFER && b.declineCode() != null && !"DECLINED".equals(b.finalOutcome())) {
            throw ApiException.invalid("declineCode needs finalOutcome DECLINED");
        }
        return p;
    }

    private static void range(Integer v, int min, int max, String name) {
        if (v != null && (v < min || v > max)) {
            throw ApiException.invalid(name + " must be between " + min + " and " + max);
        }
    }

    private Optional<RunRow> row(String runId) {
        return jdbc.query("SELECT run_id, description, active, max_uses, used_count, created_at, expires_at FROM sim_scenario_run "
                + "WHERE run_id = ?", (rs, n) -> new RunRow(rs.getString("run_id"), rs.getString("description"),
                rs.getBoolean("active"), (Integer) rs.getObject("max_uses"), rs.getInt("used_count"),
                rs.getObject("created_at", LocalDateTime.class).toInstant(ZoneOffset.UTC),
                rs.getObject("expires_at", LocalDateTime.class) == null ? null
                        : rs.getObject("expires_at", LocalDateTime.class).toInstant(ZoneOffset.UTC)), runId).stream().findFirst();
    }

    private Kind ruleKind(String runId) {
        var kinds = jdbc.queryForList("SELECT behavior FROM sim_scenario_rule WHERE run_id = ? ORDER BY priority LIMIT 1", String.class, runId);
        return kinds.isEmpty() ? Kind.SUCCESS : Kind.valueOf(kinds.get(0));
    }

    private Integer ruleDelay(String runId) {
        var params = jdbc.queryForList("SELECT params FROM sim_scenario_rule WHERE run_id = ? ORDER BY priority LIMIT 1", String.class, runId);
        if (params.isEmpty()) {
            return null;
        }
        Object d = parse(params.get(0)).get("delaySeconds");
        return d == null ? null : ((Number) d).intValue();
    }

    private static Run toRun(RunRow r, Kind kind, Integer delay) {
        State state = !r.active() ? State.DEACTIVATED
                : r.expiresAt() != null && !r.expiresAt().isAfter(Instant.now()) ? State.EXPIRED
                : r.maxUses() != null && r.usedCount() >= r.maxUses() ? State.EXHAUSTED : State.ACTIVE;
        return new Run(r.runId(), r.description(), state, kind, delay, r.maxUses(), r.usedCount(), r.createdAt(), r.expiresAt());
    }

    private static Map<String, Object> parse(String json) {
        try {
            return JSON.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() { });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("stored scenario parameters are not valid JSON");
        }
    }

    private static String json(Map<String, Object> map) {
        try {
            return JSON.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
