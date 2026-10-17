package com.paybridge.simulator.service;

import com.paybridge.simulator.config.SimulatorProperties;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Sends queued webhooks (DMR section 9, API contract 8.4). The business event id and the stored raw body are identical on
 * every attempt; only the timestamp and the signature are fresh. Claimed rows get a 30 second visibility timeout so a
 * crashed sender's rows are picked up again, which can deliver twice: the core deduplicates by event id.
 */
@Component
public class WebhookDeliveryWorker {
    private static final Logger log = LoggerFactory.getLogger(WebhookDeliveryWorker.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final SimulatorProperties props;
    private final Clock clock;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER).version(HttpClient.Version.HTTP_1_1).build();

    public WebhookDeliveryWorker(JdbcTemplate jdbc, TransactionTemplate tx, SimulatorProperties props, Clock clock) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.props = props;
        this.clock = clock;
        if (props.getWebhook().getSecret() == null || props.getWebhook().getSecret().isBlank()) {
            throw new IllegalStateException("paybridge.simulator.webhook.secret must be set");
        }
    }

    @Scheduled(fixedDelayString = "${paybridge.simulator.delivery-interval-ms:500}")
    public void tick() {
        try {
            deliverDue();
        } catch (RuntimeException e) {
            log.warn("delivery tick failed: {}", e.getClass().getSimpleName());
        }
    }

    /** Public for tests. Returns the number of deliveries attempted. */
    public int deliverDue() {
        List<Map<String, Object>> claimed = tx.execute(status -> {
            var rows = jdbc.queryForList("SELECT id, event_id, raw_body, attempt_count FROM sim_webhook_delivery "
                    + "WHERE state = 'PENDING' AND next_attempt_at <= UTC_TIMESTAMP(6) ORDER BY next_attempt_at, id "
                    + "LIMIT 20 FOR UPDATE SKIP LOCKED");
            for (var row : rows) {
                jdbc.update("UPDATE sim_webhook_delivery SET next_attempt_at = DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 30 SECOND) WHERE id = ?",
                        row.get("id"));
            }
            return rows;
        });
        if (claimed == null) {
            return 0;
        }
        for (var row : claimed) {
            attempt(row);
        }
        return claimed.size();
    }

    private void attempt(Map<String, Object> row) {
        String id = (String) row.get("id");
        byte[] raw = ((String) row.get("raw_body")).getBytes(StandardCharsets.UTF_8);
        long timestamp = clock.millis() / 1000;
        Integer status = null;
        String error = null;
        try {
            var request = HttpRequest.newBuilder(URI.create(props.getWebhook().getTargetUrl())).timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .header("X-PB-Event-Id", (String) row.get("event_id"))
                    .header("X-PB-Timestamp", Long.toString(timestamp))
                    .header("X-PB-Signature", WebhookEvents.signature(props.getWebhook().getSecret(), timestamp, raw))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(raw)).build();
            status = http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (java.io.IOException e) {
            error = e.getClass().getSimpleName();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            error = "interrupted";
        }
        if (status != null && status / 100 == 2) {
            jdbc.update("UPDATE sim_webhook_delivery SET state = 'DELIVERED', delivered_at = UTC_TIMESTAMP(6), "
                    + "last_http_status = ?, last_error = NULL, attempt_count = attempt_count + 1 WHERE id = ?", status, id);
            return;
        }
        int attempts = ((Number) row.get("attempt_count")).intValue() + 1;
        if (attempts >= props.getWebhook().getMaxAttempts()) {
            jdbc.update("UPDATE sim_webhook_delivery SET state = 'FAILED', attempt_count = ?, last_http_status = ?, last_error = ? WHERE id = ?",
                    attempts, status, error == null ? "HTTP " + status : error, id);
            log.warn("webhook delivery {} failed after {} attempts", id, attempts);
            return;
        }
        var schedule = props.getWebhook().getRetryScheduleSeconds();
        int delay = schedule.get(Math.min(attempts - 1, schedule.size() - 1));
        jdbc.update("UPDATE sim_webhook_delivery SET attempt_count = ?, last_http_status = ?, last_error = ?, "
                + "next_attempt_at = DATE_ADD(UTC_TIMESTAMP(6), INTERVAL ? SECOND) WHERE id = ?",
                attempts, status, error == null ? "HTTP " + status : error, delay, id);
    }
}
