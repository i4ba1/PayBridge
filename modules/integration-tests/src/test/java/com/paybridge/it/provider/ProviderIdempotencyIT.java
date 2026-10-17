package com.paybridge.it.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.paybridge.application.port.BillingProvider;
import com.paybridge.application.port.BillingProvider.ErrorClass;
import com.paybridge.application.port.BillingProvider.ProviderResult;
import com.paybridge.application.port.BillingProvider.ProviderResult.Kind;
import com.paybridge.domain.payment.ProviderOutcome;
import com.paybridge.it.support.SimulatorSupport;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/** FR-028: the provider key is the identity of a charge. Repeats are one operation; a changed field is a conflict. */
class ProviderIdempotencyIT extends SimulatorIT {
    private static final String CARD = "tok_sim_card_default";

    @Test
    void twentyIdenticalConcurrentCallsCreateExactlyOneCharge() throws Exception {
        String pay = newPaymentId();
        String key = BillingProvider.providerKey(pay);
        var pool = Executors.newFixedThreadPool(20);
        try {
            var calls = new ArrayList<Callable<ProviderResult>>();
            for (int i = 0; i < 20; i++) {
                calls.add(() -> provider().charge(charge(pay, 150_000, CARD, "default")));
            }
            var futures = new ArrayList<Future<ProviderResult>>(pool.invokeAll(calls));
            var references = new ArrayList<String>();
            for (var f : futures) {
                ProviderResult r = f.get();
                assertThat(r.kind()).as(r.detail()).isEqualTo(Kind.OBSERVED);
                assertThat(r.observation().outcome()).isEqualTo(ProviderOutcome.SUCCEEDED);
                assertThat(r.observation().providerRevision()).isEqualTo(1L);
                references.add(r.observation().providerReference());
            }
            assertThat(references).hasSize(20).containsOnly(references.get(0));
        } finally {
            pool.shutdownNow();
        }
        assertThat(charges(pay)).isOne();
        assertThat(simJdbc.queryForObject("SELECT hits FROM sim_key_counter WHERE idempotency_key = ?", Integer.class, key)).isEqualTo(20);
        await("the single webhook", Duration.ofSeconds(10), () -> receiver.deliveriesFor(key).size() >= 1);
        Thread.sleep(600);
        assertThat(receiver.deliveriesFor(key)).as("one financial effect, one event").hasSize(1);
        assertThat(simJdbc.queryForObject("SELECT COUNT(*) FROM sim_webhook_delivery d JOIN sim_charge c ON c.id = d.charge_id "
                + "WHERE c.idempotency_key = ?", Integer.class, key)).isOne();
    }

    @Test
    void aRepeatReturnsTheStoredChargeWithTheSameRevision() {
        String pay = newPaymentId();
        var first = provider().charge(charge(pay, 5_000, CARD, "default"));
        var second = provider().charge(charge(pay, 5_000, CARD, "default"));
        assertThat(first.httpStatus()).isEqualTo(201);
        assertThat(second.httpStatus()).isEqualTo(200);
        assertThat(second.observation()).isEqualTo(first.observation());
        assertThat(lookupResult(pay, 5_000).observation()).isEqualTo(first.observation());
    }

    @Test
    void aChangedImmutableFieldConflictsAndLeavesTheOriginalUntouched() {
        String pay = newPaymentId();
        var original = provider().charge(charge(pay, 150_000, CARD, "default"));

        assertThat(provider().charge(charge(pay, 150_001, CARD, "default")).kind()).as("amount").isEqualTo(Kind.IDEMPOTENCY_CONFLICT);
        assertThat(provider().charge(charge(pay, 150_000, "tok_sim_card_alt", "default")).kind()).as("method token").isEqualTo(Kind.IDEMPOTENCY_CONFLICT);
        var otherCurrency = new BillingProvider.ChargeRequest(pay, MERCHANT, INVOICE, 150_000, "USD", CARD, "default", null);
        assertThat(provider().charge(otherCurrency).kind()).as("currency").isEqualTo(Kind.IDEMPOTENCY_CONFLICT);
        var otherInvoice = http("POST", "/v1/charges", SimulatorSupport.TOKEN,
                body(pay, MERCHANT, "00000000-0000-4000-8000-000000000402", "150000", "IDR", CARD, "default"), "Idempotency-Key", "pbk-" + pay);
        assertThat(otherInvoice.status()).as("invoice").isEqualTo(409);
        assertThat(otherInvoice.body().path("error").path("code").asText()).isEqualTo("idempotency_conflict");
        var otherMerchant = http("POST", "/v1/charges", SimulatorSupport.TOKEN,
                body(pay, "00000000-0000-4000-8000-000000000302", INVOICE, "150000", "IDR", CARD, "default"), "Idempotency-Key", "pbk-" + pay);
        assertThat(otherMerchant.status()).as("merchant").isEqualTo(409);

        assertThat(charges(pay)).isOne();
        assertThat(simJdbc.queryForObject("SELECT amount_minor FROM sim_charge WHERE idempotency_key = ?", Long.class, "pbk-" + pay))
                .isEqualTo(150_000L);
        assertThat(lookupResult(pay, 150_000).observation().providerReference()).isEqualTo(original.observation().providerReference());
    }

    @Test
    void theKeyHeaderMustBePresentAndEqualPbkPaymentId() {
        String pay = newPaymentId();
        String valid = body(pay, MERCHANT, INVOICE, "1000", "IDR", CARD, "default");
        assertThat(http("POST", "/v1/charges", SimulatorSupport.TOKEN, valid).status()).as("no key").isEqualTo(400);
        assertThat(http("POST", "/v1/charges", SimulatorSupport.TOKEN, valid, "Idempotency-Key", "pbk-" + newPaymentId()).status()).as("wrong key").isEqualTo(400);
        assertThat(http("POST", "/v1/charges", SimulatorSupport.TOKEN, valid, "Idempotency-Key", pay).status()).as("no prefix").isEqualTo(400);
        assertThat(http("POST", "/v1/charges", SimulatorSupport.TOKEN, valid, "Idempotency-Key", "pbk-" + pay + "x".repeat(40)).status()).as("too long").isEqualTo(400);
        assertThat(charges(pay)).isZero();
    }

    @Test
    void invalidFieldsAreRejectedBeforeAnythingIsStored() {
        for (String[] bad : new String[][] {
                {"0", "IDR", CARD}, {"-5", "IDR", CARD}, {"12.5", "IDR", CARD}, {"abc", "IDR", CARD}, {"1000", "EUR", CARD},
                {"1000", "IDR", "tok_live_4111111111111111"}, {"1000", "IDR", ""}, {"9999999999999999999", "IDR", CARD}}) {
            String pay = newPaymentId();
            var r = http("POST", "/v1/charges", SimulatorSupport.TOKEN, body(pay, MERCHANT, INVOICE, bad[0], bad[1], bad[2], "default"),
                    "Idempotency-Key", "pbk-" + pay);
            assertThat(r.status()).as(String.join("/", bad)).isEqualTo(400);
            assertThat(r.body().path("error").path("code").asText()).isEqualTo("invalid_request");
            assertThat(charges(pay)).isZero();
        }
        String pay = newPaymentId();
        assertThat(http("POST", "/v1/charges", SimulatorSupport.TOKEN, "{\"paymentId\":\"" + pay + "\",\"extra\":1}", "Idempotency-Key", "pbk-" + pay).status())
                .as("unknown property").isEqualTo(400);
        assertThat(http("POST", "/v1/charges", SimulatorSupport.TOKEN, "not json", "Idempotency-Key", "pbk-" + pay).status()).isEqualTo(400);
    }

    @Test
    void aWrongTokenIsAClientErrorAndCreatesNothing() {
        String pay = newPaymentId();
        var wrong = new com.paybridge.adapter.provider.SimulatorBillingProvider(simulator.baseUri(), "wrong-token");
        var r = wrong.charge(charge(pay, 1000, CARD, "default"));
        assertThat(r.kind()).isEqualTo(Kind.CLIENT_ERROR);
        assertThat(r.httpStatus()).isEqualTo(401);
        assertThat(r.errorClass()).isEqualTo(ErrorClass.NONE);
        assertThat(charges(pay)).isZero();
        assertThat(http("POST", "/v1/charges", null, "{}").status()).isEqualTo(401);
    }

    @Test
    void lookupOfAnUnknownKeyIsNotFoundNeverADecline() {
        var r = lookupResult(newPaymentId(), 1000);
        assertThat(r.kind()).isEqualTo(Kind.NOT_FOUND);
        assertThat(r.observation()).isNull();
        List<Integer> statuses = List.of(http("GET", "/v1/charges/by-key/pbk-unknown", SimulatorSupport.TOKEN, null).status());
        assertThat(statuses).containsExactly(404);
    }

    @Test
    void aLookupAboutAnotherAmountIsNotAcceptedAsEvidence() {
        String pay = newPaymentId();
        provider().charge(charge(pay, 7_000, CARD, "default"));
        var r = lookupResult(pay, 7_001);
        assertThat(r.errorClass()).isEqualTo(ErrorClass.MALFORMED);
        assertThat(r.observation()).isNull();
    }

    static String body(String pay, String merchant, String invoice, String amount, String currency, String token, String run) {
        return "{\"paymentId\":\"" + pay + "\",\"merchantId\":\"" + merchant + "\",\"invoiceId\":\"" + invoice + "\",\"amountMinor\":\""
                + amount + "\",\"currency\":\"" + currency + "\",\"methodToken\":\"" + token + "\",\"scenarioRunId\":\"" + run + "\"}";
    }
}
