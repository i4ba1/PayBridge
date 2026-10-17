package com.paybridge.it.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.paybridge.application.port.BillingProvider;
import com.paybridge.domain.payment.ProviderOutcome;
import com.paybridge.it.support.SimulatorSupport;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Delayed work lives in the database, so a restarted simulator resumes confirmations and webhook delivery (FR-029). */
class SimulatorRestartIT extends SimulatorIT {

    @Test
    void aPendingTransferCompletesAndIsAnnouncedAfterTheSimulatorRestarts() throws Exception {
        createRun("{\"runId\":\"run-slow\",\"scenario\":\"DELAYED_TRANSFER\",\"delaySeconds\":3,\"finalOutcome\":\"SUCCEEDED\"}");
        String pay = newPaymentId();
        String key = BillingProvider.providerKey(pay);
        var r = provider().charge(charge(pay, 450_000, "tok_sim_transfer_default", "run-slow"));
        assertThat(r.observation().outcome()).isEqualTo(ProviderOutcome.PENDING);

        // Stop before the confirmation is due, stay down past it, then start again on the same database.
        simulator.close();
        Thread.sleep(4000);
        assertThat(simJdbc.queryForObject("SELECT status FROM sim_charge WHERE idempotency_key = ?", String.class, key))
                .as("nothing completed while the simulator was down").isEqualTo("PENDING");
        simulator = SimulatorSupport.start(receiver.url());

        await("resumed confirmation", Duration.ofSeconds(15),
                () -> lookupResult(pay, 450_000).observation().outcome() == ProviderOutcome.SUCCEEDED);
        assertThat(lookupResult(pay, 450_000).observation().providerRevision()).isEqualTo(2L);
        await("resumed webhook", Duration.ofSeconds(15), () -> receiver.deliveriesFor(key).size() == 1);
        assertThat(receiver.deliveriesFor(key).get(0).rawBody()).contains("\"revision\":2").contains("charge.succeeded");
    }

    @Test
    void anUndeliveredWebhookIsSentAfterARestart() throws Exception {
        receiver.answerWith(503);
        String pay = newPaymentId();
        String key = BillingProvider.providerKey(pay);
        provider().charge(charge(pay, 20_000, "tok_sim_card_default", "default"));
        await("first failed attempt", Duration.ofSeconds(10), () -> !receiver.deliveriesFor(key).isEmpty());
        var eventId = receiver.deliveriesFor(key).get(0).eventId();

        simulator.close();
        receiver.answerWith(200);
        // Rows claimed before the stop keep their visibility timeout; make the row due so the restart can pick it up.
        simJdbc.update("UPDATE sim_webhook_delivery SET next_attempt_at = UTC_TIMESTAMP(6) WHERE state = 'PENDING'");
        simulator = SimulatorSupport.start(receiver.url());

        await("delivery after restart", Duration.ofSeconds(15),
                () -> receiver.deliveriesFor(key).stream().anyMatch(d -> d.eventId().equals(eventId) && true)
                        && "DELIVERED".equals(simJdbc.queryForList("SELECT d.state FROM sim_webhook_delivery d JOIN sim_charge c ON c.id = d.charge_id "
                        + "WHERE c.idempotency_key = ?", String.class, key).get(0)));
    }
}
