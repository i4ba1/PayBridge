package com.paybridge.it.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.paybridge.adapter.provider.WebhookSignatureVerifier;
import com.paybridge.application.port.BillingProvider;
import com.paybridge.application.port.BillingProvider.ErrorClass;
import com.paybridge.application.port.BillingProvider.ProviderResult;
import com.paybridge.application.port.BillingProvider.ProviderResult.Kind;
import com.paybridge.domain.payment.ProviderOutcome;
import com.paybridge.it.support.SimulatorSupport;
import com.paybridge.it.support.WebhookReceiver.Delivery;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

/**
 * The five scenarios of API contract 4.8 through the real adapter and the real simulator, with the signed webhook the
 * core would receive. A transcript is written to build/scenario-transcript.txt for the task evidence.
 */
class SimulatorScenarioIT extends SimulatorIT {
    private static final String CARD = "tok_sim_card_default";
    private static final String TRANSFER = "tok_sim_transfer_default";
    private static final List<String> TRANSCRIPT = new ArrayList<>();

    private static void note(String line) {
        TRANSCRIPT.add(line);
    }

    @AfterAll
    static void writeTranscript() throws IOException {
        Files.createDirectories(Path.of("build"));
        Files.writeString(Path.of("build", "scenario-transcript.txt"), String.join("\n", TRANSCRIPT) + "\n", StandardCharsets.UTF_8);
    }

    private static String describe(ProviderResult r) {
        return r.kind() + (r.observation() == null ? "" : " " + r.observation().outcome() + " rev=" + r.observation().providerRevision())
                + (r.declineCode() == null ? "" : " decline=" + r.declineCode())
                + (r.errorClass() == ErrorClass.NONE ? "" : " class=" + r.errorClass())
                + (r.httpStatus() == null ? " (no HTTP response)" : " http=" + r.httpStatus());
    }

    private static Delivery awaitDelivery(String key, int count) {
        await("webhook " + count + " for " + key, Duration.ofSeconds(15), () -> receiver.deliveriesFor(key).size() >= count);
        return receiver.deliveriesFor(key).get(count - 1);
    }

    private static JsonNode verifiedBody(Delivery d) throws IOException {
        var verifier = new WebhookSignatureVerifier(List.of(SimulatorSupport.WEBHOOK_SECRET), Clock.systemUTC());
        assertThat(verifier.verify(d.timestamp(), d.signature(), d.rawBody().getBytes(StandardCharsets.UTF_8)))
                .isEqualTo(WebhookSignatureVerifier.Result.VALID);
        return JSON.readTree(d.rawBody());
    }

    @Test
    void scenario1_successIsAuthoritativeAndSignedOnce() throws Exception {
        String pay = newPaymentId();
        String key = BillingProvider.providerKey(pay);
        var r = provider().charge(charge(pay, 150_000, CARD, "default"));
        note("1 SUCCESS    charge: " + describe(r));
        assertThat(r.observation().outcome()).isEqualTo(ProviderOutcome.SUCCEEDED);
        assertThat(r.observation().providerRevision()).isEqualTo(1L);
        assertThat(r.httpStatus()).isEqualTo(201);

        var d = awaitDelivery(key, 1);
        var body = verifiedBody(d);
        note("1 SUCCESS    webhook: " + body.path("eventType").asText() + " rev=" + body.path("revision").asInt() + " signature=VALID");
        assertThat(body.path("eventType").asText()).isEqualTo("charge.succeeded");
        assertThat(body.path("providerKey").asText()).isEqualTo(key);
        assertThat(body.path("reference").asText()).isEqualTo(r.observation().providerReference());
        assertThat(body.path("revision").asLong()).isEqualTo(1);
        assertThat(body.path("amountMinor").asText()).isEqualTo("150000");
        assertThat(body.path("eventId").asText()).isEqualTo(d.eventId());
    }

    @Test
    void scenario2_declineIsABusinessOutcomeWithHttp200() throws Exception {
        createRun("{\"runId\":\"run-decline\",\"scenario\":\"DECLINE\",\"declineCode\":\"CARD_DECLINED\"}");
        String pay = newPaymentId();
        var r = provider().charge(charge(pay, 150_000, CARD, "run-decline"));
        note("2 DECLINE    charge: " + describe(r));
        assertThat(r.observation().outcome()).isEqualTo(ProviderOutcome.DECLINED);
        assertThat(r.declineCode()).isEqualTo("CARD_DECLINED");
        assertThat(r.httpStatus()).isEqualTo(200);
        var body = verifiedBody(awaitDelivery(BillingProvider.providerKey(pay), 1));
        note("2 DECLINE    webhook: " + body.path("eventType").asText() + " outcome=" + body.path("outcome").asText());
        assertThat(body.path("eventType").asText()).isEqualTo("charge.declined");
        assertThat(lookupResult(pay, 150_000).declineCode()).isEqualTo("CARD_DECLINED");
    }

    @Test
    void scenario3_delayedTransferIsPendingThenFinalWithRevisionTwo() throws Exception {
        String pay = newPaymentId();
        String key = BillingProvider.providerKey(pay);
        var r = provider().charge(charge(pay, 275_000, TRANSFER, "default"));
        note("3 DELAYED    charge: " + describe(r));
        assertThat(r.observation().outcome()).isEqualTo(ProviderOutcome.PENDING);
        assertThat(r.observation().providerRevision()).isEqualTo(1L);
        assertThat(r.httpStatus()).isEqualTo(202);
        var pending = lookupResult(pay, 275_000);
        note("3 DELAYED    lookup right after: " + describe(pending));
        assertThat(pending.observation().outcome()).isEqualTo(ProviderOutcome.PENDING);
        assertThat(receiver.deliveriesFor(key)).as("no event for a pending charge").isEmpty();

        await("final state", Duration.ofSeconds(15), () -> lookupResult(pay, 275_000).observation().outcome() == ProviderOutcome.SUCCEEDED);
        var done = lookupResult(pay, 275_000);
        note("3 DELAYED    lookup after delay: " + describe(done));
        assertThat(done.observation().providerRevision()).isEqualTo(2L);
        var body = verifiedBody(awaitDelivery(key, 1));
        note("3 DELAYED    webhook: " + body.path("eventType").asText() + " rev=" + body.path("revision").asInt());
        assertThat(body.path("revision").asLong()).isEqualTo(2);
        assertThat(receiver.deliveriesFor(key)).hasSize(1);
    }

    @Test
    void scenario4_timeoutBeforeProcessingPersistsNothingThenSucceedsOnTheNextCall() throws Exception {
        createRun("{\"runId\":\"run-timeout\",\"scenario\":\"TIMEOUT_BEFORE_PROCESSING\",\"failFirst\":1,\"sleepSeconds\":3}");
        String pay = newPaymentId();
        long start = System.nanoTime();
        var first = provider(Duration.ofMillis(500)).charge(charge(pay, 90_000, CARD, "run-timeout"));
        note("4 TIMEOUT    first charge (client timeout 500 ms): " + describe(first));
        assertThat(first.errorClass()).isEqualTo(ErrorClass.TRANSPORT_UNCERTAIN);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
        assertThat(charges(pay)).as("nothing was persisted").isZero();
        var lookup = lookupResult(pay, 90_000);
        note("4 TIMEOUT    lookup: " + describe(lookup));
        assertThat(lookup.kind()).isEqualTo(Kind.NOT_FOUND);

        var second = provider().charge(charge(pay, 90_000, CARD, "run-timeout"));
        note("4 TIMEOUT    second charge, same key: " + describe(second));
        assertThat(second.observation().outcome()).isEqualTo(ProviderOutcome.SUCCEEDED);
        assertThat(charges(pay)).isOne();
        assertThat(simJdbc.queryForObject("SELECT hits FROM sim_key_counter WHERE idempotency_key = ?", Integer.class,
                BillingProvider.providerKey(pay))).isEqualTo(2);
    }

    @Test
    void scenario5_successThenDroppedResponseIsResolvedByLookupToTheOriginalCharge() throws Exception {
        createRun("{\"runId\":\"run-lost\",\"scenario\":\"SUCCESS_RESPONSE_LOST\",\"failFirst\":1}");
        String pay = newPaymentId();
        String key = BillingProvider.providerKey(pay);
        var first = provider().charge(charge(pay, 120_000, CARD, "run-lost"));
        note("5 LOST       first charge: " + describe(first));
        assertThat(first.kind()).isEqualTo(Kind.NO_EVIDENCE);
        assertThat(first.errorClass()).isEqualTo(ErrorClass.TRANSPORT_UNCERTAIN);
        assertThat(first.httpStatus()).as("no response was received").isNull();
        assertThat(charges(pay)).as("the charge is committed even though the answer was lost").isOne();

        var lookup = lookupResult(pay, 120_000);
        note("5 LOST       lookup: " + describe(lookup) + " reference=" + lookup.observation().providerReference());
        assertThat(lookup.observation().outcome()).isEqualTo(ProviderOutcome.SUCCEEDED);
        assertThat(lookup.observation().providerRevision()).isEqualTo(1L);

        var retry = provider().charge(charge(pay, 120_000, CARD, "run-lost"));
        note("5 LOST       retry, same key: " + describe(retry));
        assertThat(retry.observation().providerReference()).isEqualTo(lookup.observation().providerReference());
        assertThat(charges(pay)).as("exactly one provider financial effect").isOne();
        awaitDelivery(key, 1);
        Thread.sleep(500);
        assertThat(receiver.deliveriesFor(key)).hasSize(1);
    }

    @Test
    void runsAreIndependentAndNeverChangeAnotherPaymentsOutcome() throws Exception {
        createRun("{\"runId\":\"run-a\",\"scenario\":\"DECLINE\",\"declineCode\":\"SUSPECTED_FRAUD\"}");
        createRun("{\"runId\":\"run-b\",\"scenario\":\"SUCCESS\"}");
        var pool = Executors.newFixedThreadPool(8);
        try {
            var jobs = new ArrayList<java.util.concurrent.Future<String>>();
            for (int i = 0; i < 12; i++) {
                String run = i % 3 == 0 ? "run-a" : i % 3 == 1 ? "run-b" : "default";
                String pay = newPaymentId();
                jobs.add(pool.submit(() -> run + ":" + provider().charge(charge(pay, 10_000, CARD, run)).observation().outcome()));
            }
            for (var j : jobs) {
                String[] parts = j.get().split(":");
                String expected = parts[0].equals("run-a") ? "DECLINED" : "SUCCEEDED";
                assertThat(parts[1]).as(parts[0]).isEqualTo(expected);
            }
        } finally {
            pool.shutdownNow();
        }
        note("6 ISOLATION  12 concurrent charges over run-a (decline), run-b (success) and default (success): each run kept its own outcome");
    }
}
