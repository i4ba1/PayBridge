package com.paybridge.it.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.paybridge.application.port.BillingProvider;
import com.paybridge.application.port.BillingProvider.ProviderResult.Kind;
import com.paybridge.it.support.SimulatorSupport;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** An unknown, expired, deactivated or exhausted run is rejected with 422 and never replaced by the default run. */
class ScenarioRunRejectionIT extends SimulatorIT {
    private static final String CARD = "tok_sim_card_default";

    private void assertRejectedAndNothingStored(String pay, String run) {
        var r = provider().charge(charge(pay, 10_000, CARD, run));
        assertThat(r.kind()).isEqualTo(Kind.SCENARIO_NOT_AVAILABLE);
        assertThat(r.httpStatus()).isEqualTo(422);
        assertThat(r.observation()).isNull();
        assertThat(charges(pay)).as("no charge exists").isZero();
        assertThat(simJdbc.queryForObject("SELECT COUNT(*) FROM sim_key_counter WHERE idempotency_key = ?", Integer.class,
                BillingProvider.providerKey(pay))).as("not even a counter row").isZero();
        assertThat(lookupResult(pay, 10_000).kind()).isEqualTo(Kind.NOT_FOUND);
    }

    @Test
    void anUnknownRunIsRejectedNotDefaulted() {
        String pay = newPaymentId();
        assertRejectedAndNothingStored(pay, "run-that-does-not-exist");
        var raw = http("POST", "/v1/charges", SimulatorSupport.TOKEN,
                ProviderIdempotencyIT.body(newPaymentId(), MERCHANT, INVOICE, "1000", "IDR", CARD, "nope"), "Idempotency-Key", "pbk-x");
        assertThat(raw.status()).as("a key that does not match is still a plain 400").isEqualTo(400);
    }

    @Test
    void theRejectionBodyHasTheDocumentedErrorCode() {
        String pay = newPaymentId();
        var raw = http("POST", "/v1/charges", SimulatorSupport.TOKEN,
                ProviderIdempotencyIT.body(pay, MERCHANT, INVOICE, "1000", "IDR", CARD, "unknown-run"), "Idempotency-Key", "pbk-" + pay);
        assertThat(raw.status()).isEqualTo(422);
        assertThat(raw.body().path("error").path("code").asText()).isEqualTo("SCENARIO_NOT_AVAILABLE");
    }

    @Test
    void anExpiredRunIsRejected() throws Exception {
        createRun("{\"runId\":\"run-expiring\",\"scenario\":\"SUCCESS\",\"expiresAt\":\"" + Instant.now().plusSeconds(2) + "\"}");
        String before = newPaymentId();
        assertThat(provider().charge(charge(before, 10_000, CARD, "run-expiring")).isObserved()).isTrue();
        Thread.sleep(2600);
        assertThat(admin("GET", "/internal/scenario-runs/run-expiring", null).body().path("state").asText()).isEqualTo("EXPIRED");
        assertRejectedAndNothingStored(newPaymentId(), "run-expiring");
    }

    @Test
    void aDeactivatedRunIsRejectedAndDeactivationIsIdempotent() {
        createRun("{\"runId\":\"run-off\",\"scenario\":\"SUCCESS\"}");
        assertThat(admin("POST", "/internal/scenario-runs/run-off/deactivate", null).status()).isEqualTo(200);
        var again = admin("POST", "/internal/scenario-runs/run-off/deactivate", null);
        assertThat(again.status()).isEqualTo(200);
        assertThat(again.body().path("state").asText()).isEqualTo("DEACTIVATED");
        assertRejectedAndNothingStored(newPaymentId(), "run-off");
    }

    @Test
    void anExhaustedRunRejectsNewKeysButStillServesTheKeysItAlreadyCounted() {
        createRun("{\"runId\":\"run-once\",\"scenario\":\"SUCCESS\",\"maxUses\":1}");
        String first = newPaymentId();
        assertThat(provider().charge(charge(first, 10_000, CARD, "run-once")).isObserved()).isTrue();
        assertThat(admin("GET", "/internal/scenario-runs/run-once", null).body().path("state").asText()).isEqualTo("EXHAUSTED");
        assertThat(admin("GET", "/internal/scenario-runs/run-once", null).body().path("usedCount").asInt()).isEqualTo(1);

        assertThat(provider().charge(charge(first, 10_000, CARD, "run-once")).httpStatus()).as("the same key again").isEqualTo(200);
        assertRejectedAndNothingStored(newPaymentId(), "run-once");
        assertThat(admin("GET", "/internal/scenario-runs/run-once", null).body().path("usedCount").asInt()).as("a rejection uses nothing").isEqualTo(1);
    }

    @Test
    void usedCountGrowsOncePerDistinctKey() {
        createRun("{\"runId\":\"run-count\",\"scenario\":\"SUCCESS\",\"maxUses\":5}");
        String a = newPaymentId();
        String b = newPaymentId();
        for (int i = 0; i < 4; i++) {
            provider().charge(charge(a, 10_000, CARD, "run-count"));
        }
        provider().charge(charge(b, 10_000, CARD, "run-count"));
        var run = admin("GET", "/internal/scenario-runs/run-count", null).body();
        assertThat(run.path("usedCount").asInt()).isEqualTo(2);
        assertThat(run.path("state").asText()).isEqualTo("ACTIVE");
        assertThat(run.path("maxUses").asInt()).isEqualTo(5);
    }

    @Test
    void theDefaultRunIsAlwaysValidAndCannotBeEdited() {
        assertThat(provider().charge(charge(newPaymentId(), 10_000, CARD, "default")).isObserved()).isTrue();
        assertThat(provider().charge(charge(newPaymentId(), 10_000, CARD, null)).isObserved()).as("absent means default").isTrue();
        assertThat(admin("POST", "/internal/scenario-runs", "{\"runId\":\"default\",\"scenario\":\"DECLINE\"}").status()).isEqualTo(409);
        assertThat(admin("POST", "/internal/scenario-runs/default/deactivate", null).status()).isEqualTo(409);
        assertThat(admin("GET", "/internal/scenario-runs/default", null).body().path("state").asText()).isEqualTo("ACTIVE");
    }

    @Test
    void runCreationRejectsUnknownAndMisplacedParameters() {
        String[] bad = {
                "{\"runId\":\"r1\",\"scenario\":\"SUCCESS\",\"bogus\":1}",
                "{\"runId\":\"r2\",\"scenario\":\"DECLINE\",\"delaySeconds\":5}",
                "{\"runId\":\"r3\",\"scenario\":\"DECLINE\",\"declineCode\":\"NOT_A_CODE\"}",
                "{\"runId\":\"r4\",\"scenario\":\"NO_SUCH\"}",
                "{\"runId\":\"bad id!\",\"scenario\":\"SUCCESS\"}",
                "{\"runId\":\"r5\",\"scenario\":\"SUCCESS\",\"maxUses\":0}",
                "{\"runId\":\"r6\",\"scenario\":\"TIMEOUT_BEFORE_PROCESSING\",\"sleepSeconds\":9999}",
                "{\"runId\":\"r7\",\"scenario\":\"SUCCESS\",\"expiresAt\":\"2020-01-01T00:00:00Z\"}",
                "{\"runId\":\"r8\",\"scenario\":\"DELAYED_TRANSFER\",\"declineCode\":\"CARD_DECLINED\"}",
                "{\"scenario\":\"SUCCESS\"}",
        };
        for (String b : bad) {
            var r = admin("POST", "/internal/scenario-runs", b);
            assertThat(r.status()).as(b).isEqualTo(400);
            assertThat(r.body().path("error").path("code").asText()).as(b).isEqualTo("invalid_request");
        }
        assertThat(admin("GET", "/internal/scenario-runs/r1", null).status()).as("nothing was stored").isEqualTo(404);
    }

    @Test
    void aDuplicateRunIdConflicts() {
        createRun("{\"runId\":\"run-dup\",\"scenario\":\"SUCCESS\"}");
        assertThat(admin("POST", "/internal/scenario-runs", "{\"runId\":\"run-dup\",\"scenario\":\"DECLINE\"}").status()).isEqualTo(409);
        assertThat(admin("GET", "/internal/scenario-runs/run-dup", null).body().path("scenario").asText()).isEqualTo("SUCCESS");
        assertThat(admin("GET", "/internal/scenario-runs/never-created", null).status()).isEqualTo(404);
    }
}
