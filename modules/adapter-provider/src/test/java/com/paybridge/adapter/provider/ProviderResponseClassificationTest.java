package com.paybridge.adapter.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.paybridge.adapter.provider.ProviderResponseClassifier.Expectation;
import com.paybridge.application.port.BillingProvider.ErrorClass;
import com.paybridge.application.port.BillingProvider.ProviderResult;
import com.paybridge.application.port.BillingProvider.ProviderResult.Kind;
import com.paybridge.domain.payment.ProviderOutcome;
import java.io.EOFException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** One class per outcome (API contract 8.5). HTTP status alone never decides. */
class ProviderResponseClassificationTest {
    private static final String KEY = "pbk-00000000-0000-4000-8000-000000000201";
    private static final Expectation EXPECTED = new Expectation(KEY, 150000, "IDR", null);

    private static String body(String status, int revision) {
        return body(KEY, status, revision, "150000", "IDR", status.equals("DECLINED") ? "\"CARD_DECLINED\"" : "null");
    }

    private static String body(String key, String status, Object revision, String amount, String currency, String decline) {
        return "{\"reference\":\"sim_ch_9f2c41d7a0b39f2c41d7\",\"providerKey\":\"" + key + "\",\"status\":\"" + status
                + "\",\"revision\":" + revision + ",\"amountMinor\":\"" + amount + "\",\"currency\":\"" + currency
                + "\",\"declineCode\":" + decline + ",\"createdAt\":\"2026-10-16T02:00:10.300Z\",\"updatedAt\":\"2026-10-16T02:00:10.340Z\"}";
    }

    private static ProviderResult charge(int status, String body) {
        return ProviderResponseClassifier.classifyCharge(status, body, null, EXPECTED);
    }

    @Test
    void confirmedSuccessNeedsAMatchingBody() {
        var r = charge(201, body("SUCCEEDED", 2));
        assertThat(r.kind()).isEqualTo(Kind.OBSERVED);
        assertThat(r.observation().outcome()).isEqualTo(ProviderOutcome.SUCCEEDED);
        assertThat(r.observation().providerRevision()).isEqualTo(2);
        assertThat(r.observation().providerReference()).startsWith("sim_ch_");
        assertThat(charge(200, body("SUCCEEDED", 2)).isObserved()).isTrue();
    }

    @Test
    void aDeclineIsABusinessOutcomeNotAnError() {
        var r = charge(200, body("DECLINED", 1));
        assertThat(r.kind()).isEqualTo(Kind.OBSERVED);
        assertThat(r.observation().outcome()).isEqualTo(ProviderOutcome.DECLINED);
        assertThat(r.declineCode()).isEqualTo("CARD_DECLINED");
        assertThat(charge(201, body("DECLINED", 1).replace("\"CARD_DECLINED\"", "null")).declineCode()).isEqualTo("UNSPECIFIED");
    }

    @Test
    void pendingKeepsThePaymentProcessing() {
        var r = charge(202, body("PENDING", 1));
        assertThat(r.observation().outcome()).isEqualTo(ProviderOutcome.PENDING);
        assertThat(charge(200, body("PENDING", 1)).observation().outcome()).isEqualTo(ProviderOutcome.PENDING);
    }

    @Test
    void a202WithAFinalStatusIsMalformed() {
        assertThat(charge(202, body("SUCCEEDED", 2)).errorClass()).isEqualTo(ErrorClass.MALFORMED);
    }

    @Test
    void everyInconsistencyIsMalformedNeverASuccess() {
        String[] bad = {
                "", "not json", "[]", "{}",
                body("pbk-other", "SUCCEEDED", 1, "150000", "IDR", "null"),
                body(KEY, "SUCCEEDED", 1, "150001", "IDR", "null"),
                body(KEY, "SUCCEEDED", 1, "150000", "USD", "null"),
                body(KEY, "REFUNDED", 1, "150000", "IDR", "null"),
                body(KEY, "SUCCEEDED", "\"2\"", "150000", "IDR", "null"),
                body(KEY, "SUCCEEDED", 0, "150000", "IDR", "null"),
                body(KEY, "SUCCEEDED", 1.5, "150000", "IDR", "null"),
                body(KEY, "SUCCEEDED", 1, "150000", "IDR", "\"CARD_DECLINED\""),
                "{\"providerKey\":\"" + KEY + "\",\"status\":\"SUCCEEDED\"}",
        };
        for (String b : bad) {
            var r = charge(201, b);
            assertThat(r.errorClass()).as(b).isEqualTo(ErrorClass.MALFORMED);
            assertThat(r.isObserved()).as(b).isFalse();
        }
    }

    @Test
    void aLowerRevisionThanAlreadyStoredIsMalformedButEqualOrHigherIsFine() {
        var known = new Expectation(KEY, 150000, "IDR", 2L);
        assertThat(ProviderResponseClassifier.classifyLookup(200, body("SUCCEEDED", 1), null, known).errorClass())
                .isEqualTo(ErrorClass.MALFORMED);
        assertThat(ProviderResponseClassifier.classifyLookup(200, body("SUCCEEDED", 2), null, known).isObserved()).isTrue();
        assertThat(ProviderResponseClassifier.classifyLookup(200, body("SUCCEEDED", 3), null, known).isObserved()).isTrue();
    }

    @Test
    void conflictAndScenarioRejectionAreDistinguished() {
        assertThat(charge(409, "{\"error\":{\"code\":\"idempotency_conflict\",\"message\":\"x\"}}").kind()).isEqualTo(Kind.IDEMPOTENCY_CONFLICT);
        assertThat(charge(409, "{}").errorClass()).as("a 409 without the code is not trusted").isEqualTo(ErrorClass.MALFORMED);
        assertThat(charge(422, "{\"error\":{\"code\":\"SCENARIO_NOT_AVAILABLE\",\"message\":\"x\"}}").kind()).isEqualTo(Kind.SCENARIO_NOT_AVAILABLE);
        assertThat(charge(422, "{\"error\":{\"code\":\"other\"}}").kind()).isEqualTo(Kind.CLIENT_ERROR);
    }

    @Test
    void clientErrorsAreNeverADecline() {
        for (int status : new int[] {400, 401, 403}) {
            var r = charge(status, "{\"error\":{\"code\":\"invalid_request\"}}");
            assertThat(r.kind()).isEqualTo(Kind.CLIENT_ERROR);
            assertThat(r.observation()).isNull();
        }
        assertThat(charge(404, "{}").kind()).isEqualTo(Kind.CLIENT_ERROR);
    }

    @Test
    void unavailabilityIsRetryableAndHonorsRetryAfter() {
        var r = ProviderResponseClassifier.classifyCharge(503, "", "7", EXPECTED);
        assertThat(r.errorClass()).isEqualTo(ErrorClass.RETRYABLE_UNAVAILABLE);
        assertThat(r.retryAfter()).isEqualTo(Duration.ofSeconds(7));
        assertThat(ProviderResponseClassifier.classifyCharge(429, "", "9999", EXPECTED).retryAfter()).isEqualTo(Duration.ofMinutes(5));
        assertThat(ProviderResponseClassifier.classifyCharge(429, "", "soon", EXPECTED).retryAfter()).isNull();
        assertThat(ProviderResponseClassifier.classifyCharge(429, "", "-3", EXPECTED).retryAfter()).isNull();
        assertThat(ProviderResponseClassifier.classifyCharge(429, "", null, EXPECTED).retryAfter()).isNull();
    }

    @Test
    void otherServerErrorsAreTransportUncertainty() {
        for (int status : new int[] {500, 502, 504, 599}) {
            assertThat(charge(status, "").errorClass()).as("HTTP " + status).isEqualTo(ErrorClass.TRANSPORT_UNCERTAIN);
        }
    }

    @Test
    void unexpectedStatusesAreMalformed() {
        assertThat(charge(302, "").errorClass()).isEqualTo(ErrorClass.MALFORMED);
        assertThat(charge(204, "").errorClass()).isEqualTo(ErrorClass.MALFORMED);
    }

    @Test
    void lookupNotFoundIsUncertaintyNotADecline() {
        var r = ProviderResponseClassifier.classifyLookup(404, "{\"error\":{\"code\":\"charge_not_found\"}}", null, EXPECTED);
        assertThat(r.kind()).isEqualTo(Kind.NOT_FOUND);
        assertThat(r.observation()).isNull();
        assertThat(ProviderResponseClassifier.classifyLookup(404, "<html>", null, EXPECTED).errorClass()).isEqualTo(ErrorClass.MALFORMED);
    }

    @Test
    void connectFailuresAreRetryableAndEverythingAfterSendingIsUncertain() {
        assertThat(ProviderResponseClassifier.classifyFailure(new ConnectException("refused")).errorClass()).isEqualTo(ErrorClass.RETRYABLE_UNAVAILABLE);
        assertThat(ProviderResponseClassifier.classifyFailure(new HttpConnectTimeoutException("slow")).errorClass()).isEqualTo(ErrorClass.RETRYABLE_UNAVAILABLE);
        assertThat(ProviderResponseClassifier.classifyFailure(new HttpTimeoutException("timed out")).errorClass()).isEqualTo(ErrorClass.TRANSPORT_UNCERTAIN);
        assertThat(ProviderResponseClassifier.classifyFailure(new EOFException("eof")).errorClass()).isEqualTo(ErrorClass.TRANSPORT_UNCERTAIN);
        assertThat(ProviderResponseClassifier.classifyFailure(new IOException("reset")).errorClass()).isEqualTo(ErrorClass.TRANSPORT_UNCERTAIN);
        assertThat(ProviderResponseClassifier.classifyFailure(new InterruptedException()).errorClass()).isEqualTo(ErrorClass.TRANSPORT_UNCERTAIN);
    }

    @Test
    void detailNeverEchoesTheBody() {
        var r = charge(201, "{\"secret\":\"tok_live_4242\"}");
        assertThat(r.detail()).doesNotContain("tok_live_4242");
    }
}
