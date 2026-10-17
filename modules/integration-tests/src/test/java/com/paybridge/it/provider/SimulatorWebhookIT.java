package com.paybridge.it.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.paybridge.adapter.provider.WebhookSignatureVerifier;
import com.paybridge.application.port.BillingProvider;
import com.paybridge.it.support.SimulatorSupport;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Delivery retries (API contract 8.4): the same event id and the same bytes every time, a fresh timestamp and signature
 * each time, and a bounded number of attempts. Runs its own simulator with a short retry schedule.
 */
class SimulatorWebhookIT extends SimulatorIT {
    @BeforeAll
    static void shortSchedule() {
        simulator.close();
        simulator = SimulatorSupport.start(receiver.url(), "--paybridge.simulator.webhook.retry-schedule-seconds=1,1",
                "--paybridge.simulator.webhook.max-attempts=4");
    }

    @AfterAll
    static void restoreNothing() {
        // the base class closes whatever simulator is current
    }

    @Test
    void retriesKeepTheEventAndBodyButSignAFreshTimestampEachTime() throws Exception {
        receiver.script(500, 503);
        String pay = newPaymentId();
        String key = BillingProvider.providerKey(pay);
        provider().charge(charge(pay, 10_000, "tok_sim_card_default", "default"));

        await("three attempts", Duration.ofSeconds(20), () -> receiver.deliveriesFor(key).size() >= 3);
        var attempts = receiver.deliveriesFor(key);
        assertThat(attempts).hasSize(3);
        assertThat(attempts).extracting(d -> d.eventId()).containsOnly(attempts.get(0).eventId());
        assertThat(attempts).extracting(d -> d.rawBody()).containsOnly(attempts.get(0).rawBody());
        assertThat(attempts.stream().map(d -> d.timestamp()).distinct().count()).as("fresh timestamp on retries").isGreaterThanOrEqualTo(2);
        assertThat(attempts.stream().map(d -> d.signature()).distinct().count()).as("fresh signature on retries").isGreaterThanOrEqualTo(2);

        var verifier = new WebhookSignatureVerifier(List.of(SimulatorSupport.WEBHOOK_SECRET), Clock.systemUTC());
        for (var d : attempts) {
            assertThat(verifier.verify(d.timestamp(), d.signature(), d.rawBody().getBytes(StandardCharsets.UTF_8)))
                    .isEqualTo(WebhookSignatureVerifier.Result.VALID);
        }
        await("delivered state", Duration.ofSeconds(5), () -> "DELIVERED".equals(state(key)));
        assertThat(simJdbc.queryForObject("SELECT attempt_count FROM sim_webhook_delivery d JOIN sim_charge c ON c.id = d.charge_id "
                + "WHERE c.idempotency_key = ?", Integer.class, key)).isEqualTo(3);
        Thread.sleep(1500);
        assertThat(receiver.deliveriesFor(key)).as("delivery stops after the first 2xx").hasSize(3);
    }

    @Test
    void aDeliveryThatNeverSucceedsBecomesFailedAfterTheConfiguredAttempts() throws Exception {
        receiver.script(500, 500, 500, 500, 500, 500);
        String pay = newPaymentId();
        String key = BillingProvider.providerKey(pay);
        provider().charge(charge(pay, 10_000, "tok_sim_card_default", "default"));
        await("failed state", Duration.ofSeconds(30), () -> "FAILED".equals(state(key)));
        assertThat(receiver.deliveriesFor(key)).hasSize(4);
        var row = simJdbc.queryForMap("SELECT attempt_count, last_http_status FROM sim_webhook_delivery d JOIN sim_charge c "
                + "ON c.id = d.charge_id WHERE c.idempotency_key = ?", key);
        assertThat(((Number) row.get("attempt_count")).intValue()).isEqualTo(4);
        assertThat(((Number) row.get("last_http_status")).intValue()).isEqualTo(500);
        receiver.script();
        receiver.answerWith(200);
    }

    private static String state(String key) {
        var states = simJdbc.queryForList("SELECT d.state FROM sim_webhook_delivery d JOIN sim_charge c ON c.id = d.charge_id "
                + "WHERE c.idempotency_key = ?", String.class, key);
        return states.isEmpty() ? null : states.get(0);
    }
}
