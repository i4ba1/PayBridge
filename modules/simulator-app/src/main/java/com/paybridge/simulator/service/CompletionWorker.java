package com.paybridge.simulator.service;

import com.paybridge.simulator.config.SimulatorProperties;
import com.paybridge.simulator.service.ScenarioService.Behavior;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Confirms pending transfers whose time has come (DMR section 9). All delayed state is in sim_charge, so a restart
 * resumes on the first tick. Several simulator pods can run it at once: SKIP LOCKED gives each charge to one of them.
 */
@Component
public class CompletionWorker {
    private static final Logger log = LoggerFactory.getLogger(CompletionWorker.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ScenarioService scenarios;
    private final ChargeService charges;
    private final SimulatorProperties props;
    private final Clock clock;

    public CompletionWorker(JdbcTemplate jdbc, TransactionTemplate tx, ScenarioService scenarios, ChargeService charges,
                            SimulatorProperties props, Clock clock) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.scenarios = scenarios;
        this.charges = charges;
        this.props = props;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${paybridge.simulator.completion-interval-ms:1000}")
    public void tick() {
        try {
            int done = completeDue();
            if (done > 0) {
                log.info("completed {} pending charge(s)", done);
            }
        } catch (RuntimeException e) {
            log.warn("completion tick failed: {}", e.getClass().getSimpleName());
        }
    }

    /** Public for tests that want to force a tick. Returns the number of charges completed. */
    public int completeDue() {
        Integer count = tx.execute(status -> {
            List<Map<String, Object>> due = jdbc.queryForList("SELECT id, idempotency_key, provider_reference, planned_final, "
                    + "amount_minor, currency, scenario_run_id, method_token, merchant_id, invoice_id, payment_id, revision "
                    + "FROM sim_charge WHERE status = 'PENDING' AND complete_at <= UTC_TIMESTAMP(6) "
                    + "ORDER BY complete_at, id LIMIT 20 FOR UPDATE SKIP LOCKED");
            Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
            for (Map<String, Object> row : due) {
                String fin = String.valueOf(row.get("planned_final"));
                boolean succeeded = !"DECLINED".equals(fin);
                var body = new ChargeBody((String) row.get("payment_id"), (String) row.get("merchant_id"),
                        (String) row.get("invoice_id"), Long.toString(((Number) row.get("amount_minor")).longValue()),
                        (String) row.get("currency"), (String) row.get("method_token"), (String) row.get("scenario_run_id"));
                Behavior behavior = scenarios.resolve(body.scenarioRunId(), body);
                long revision = ((Number) row.get("revision")).longValue() + 1;
                jdbc.update("UPDATE sim_charge SET status = ?, revision = ?, decline_code = ?, updated_at = ? WHERE id = ?",
                        fin, revision, succeeded ? null : ChargeService.declineCode(behavior), ChargeService.ts(now), row.get("id"));
                long delay = behavior.webhookDelayMs() >= 0 ? behavior.webhookDelayMs() : props.getWebhook().getDefaultDelayMs();
                charges.queueWebhook((String) row.get("id"), (String) row.get("idempotency_key"),
                        (String) row.get("provider_reference"), revision, succeeded, now, body.amountMinor(), body.currency(), delay);
            }
            return due.size();
        });
        return count == null ? 0 : count;
    }
}
