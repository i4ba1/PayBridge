package com.paybridge.adapter.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.paybridge.adapter.provider.WebhookSignatureVerifier.Result;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/** T11 and the simulator must agree with the worked example in API contract 8.4. */
class WebhookSignatureVerifierTest {
    static final String SECRET = "whsec_demo_0123456789abcdef";
    static final long TIMESTAMP = 1760580000L;
    static final String BODY = "{\"eventId\":\"7d5c1e0a-3b7e-4f0e-9a55-0d6f3b9c2a11\",\"eventType\":\"charge.succeeded\","
            + "\"providerKey\":\"pbk-00000000-0000-4000-8000-000000000201\",\"reference\":\"simch_9f2c41d7a0b3\","
            + "\"revision\":2,\"outcome\":\"SUCCEEDED\",\"observedAt\":\"2026-10-16T02:00:07.412Z\","
            + "\"amountMinor\":\"150000\",\"currency\":\"IDR\"}";
    static final String EXPECTED_SIGNATURE = "v1=dc8fa64a93cb7044c699414b15c7a08d7a26bd618d3cdf6dddef78e675f8210a";

    private static Clock at(long epochSeconds) {
        return Clock.fixed(Instant.ofEpochSecond(epochSeconds), ZoneOffset.UTC);
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void reproducesTheDocumentedWorkedExample() {
        assertThat(bytes(BODY)).hasSize(286);
        assertThat(WebhookSignatureVerifier.sign(SECRET, TIMESTAMP, bytes(BODY))).isEqualTo(EXPECTED_SIGNATURE);
        var v = new WebhookSignatureVerifier(List.of(SECRET), at(TIMESTAMP));
        assertThat(v.verify(Long.toString(TIMESTAMP), EXPECTED_SIGNATURE, bytes(BODY))).isEqualTo(Result.VALID);
    }

    @Test
    void anyChangeToTheBodyOrTimestampBreaksTheSignature() {
        var v = new WebhookSignatureVerifier(List.of(SECRET), at(TIMESTAMP));
        assertThat(v.verify(Long.toString(TIMESTAMP), EXPECTED_SIGNATURE, bytes(BODY + " "))).isEqualTo(Result.BAD_SIGNATURE);
        assertThat(v.verify(Long.toString(TIMESTAMP), EXPECTED_SIGNATURE, bytes(BODY.replace("150000", "150001")))).isEqualTo(Result.BAD_SIGNATURE);
        assertThat(v.verify(Long.toString(TIMESTAMP + 1), EXPECTED_SIGNATURE, bytes(BODY))).isEqualTo(Result.BAD_SIGNATURE);
        // Re-serializing the JSON (pretty printing) changes the bytes and must fail.
        assertThat(v.verify(Long.toString(TIMESTAMP), EXPECTED_SIGNATURE, bytes(BODY.replace(",", ", ")))).isEqualTo(Result.BAD_SIGNATURE);
    }

    @Test
    void theFiveMinuteWindowIsEnforcedInBothDirections() {
        String sig = EXPECTED_SIGNATURE;
        assertThat(new WebhookSignatureVerifier(List.of(SECRET), at(TIMESTAMP + 300)).verify(Long.toString(TIMESTAMP), sig, bytes(BODY))).isEqualTo(Result.VALID);
        assertThat(new WebhookSignatureVerifier(List.of(SECRET), at(TIMESTAMP + 301)).verify(Long.toString(TIMESTAMP), sig, bytes(BODY))).isEqualTo(Result.STALE);
        assertThat(new WebhookSignatureVerifier(List.of(SECRET), at(TIMESTAMP - 301)).verify(Long.toString(TIMESTAMP), sig, bytes(BODY))).isEqualTo(Result.STALE);
    }

    @Test
    void missingAndMalformedInputsAreDistinguished() {
        var v = new WebhookSignatureVerifier(List.of(SECRET), at(TIMESTAMP));
        assertThat(v.verify(null, EXPECTED_SIGNATURE, bytes(BODY))).isEqualTo(Result.MISSING);
        assertThat(v.verify("1760580000", null, bytes(BODY))).isEqualTo(Result.MISSING);
        assertThat(v.verify("1760580000", EXPECTED_SIGNATURE, null)).isEqualTo(Result.MISSING);
        assertThat(v.verify("yesterday", EXPECTED_SIGNATURE, bytes(BODY))).isEqualTo(Result.MALFORMED);
        assertThat(v.verify("1760580000", "dc8fa64a", bytes(BODY))).as("no v1= prefix").isEqualTo(Result.MALFORMED);
        assertThat(v.verify("1760580000", "v1=zz", bytes(BODY))).isEqualTo(Result.MALFORMED);
        assertThat(v.verify("1760580000", "v1=", bytes(BODY))).isEqualTo(Result.BAD_SIGNATURE);
    }

    @Test
    void thePreviousSecretIsAcceptedDuringRotation() {
        var v = new WebhookSignatureVerifier(List.of("whsec_new", SECRET), at(TIMESTAMP));
        assertThat(v.verify(Long.toString(TIMESTAMP), EXPECTED_SIGNATURE, bytes(BODY))).isEqualTo(Result.VALID);
        String signedWithNew = WebhookSignatureVerifier.sign("whsec_new", TIMESTAMP, bytes(BODY));
        assertThat(v.verify(Long.toString(TIMESTAMP), signedWithNew, bytes(BODY))).isEqualTo(Result.VALID);
        assertThat(new WebhookSignatureVerifier(List.of("whsec_new"), at(TIMESTAMP))
                .verify(Long.toString(TIMESTAMP), EXPECTED_SIGNATURE, bytes(BODY))).as("the old secret is retired").isEqualTo(Result.BAD_SIGNATURE);
    }

    @Test
    void aSignatureFromAnotherSecretIsRejected() {
        var v = new WebhookSignatureVerifier(List.of(SECRET), at(TIMESTAMP));
        assertThat(v.verify(Long.toString(TIMESTAMP), WebhookSignatureVerifier.sign("other", TIMESTAMP, bytes(BODY)), bytes(BODY)))
                .isEqualTo(Result.BAD_SIGNATURE);
    }

    @Test
    void aVerifierWithoutASecretCannotBeBuilt() {
        assertThatThrownBy(() -> new WebhookSignatureVerifier(List.of(" ", ""), at(TIMESTAMP))).isInstanceOf(IllegalArgumentException.class);
    }
}
