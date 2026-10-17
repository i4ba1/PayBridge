package com.paybridge.it.identity;

import static com.paybridge.it.support.Ops.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.paybridge.application.port.IdentityStore.GlobalRole;
import com.paybridge.it.support.CoreHttpIT;
import com.paybridge.it.support.TestKeys;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The transport gate: an unverifiable or mismatched service context is HTTP 401 with a generic body. */
class ServiceContextHttpIT extends CoreHttpIT {

    private Response withClaims(Map<String, Object> claims, String document, String operationName) {
        return post(TestKeys.sign(KEY, claims), document, Map.of(), operationName, Map.of());
    }

    @Test
    void aMissingOrMalformedAuthorizationHeaderIsRefused() {
        assertThat(post(null, ME, Map.of(), "Me", Map.of()).status()).isEqualTo(401);
        assertThat(post("not.a.jwt", ME, Map.of(), "Me", Map.of()).status()).isEqualTo(401);
        assertThat(post("garbage", ME, Map.of(), "Me", Map.of()).status()).isEqualTo(401);
    }

    @Test
    void theFailureBodyIsGenericAndNamesNoReason() {
        var r = post("not.a.jwt", ME, Map.of(), "Me", Map.of());
        assertThat(r.body().toString().toLowerCase()).doesNotContain("signature", "expired", "kid", "issuer", "audience");
    }

    @Test
    void aTokenSignedByAnUnknownKeyOrWithATamperedPayloadIsRefused() {
        var other = TestKeys.generate("console-test-1");     // same kid, different key material
        var claims = TestKeys.claims(null, null, "SetupStatus", Instant.now().getEpochSecond());
        var q = "query SetupStatus { setupStatus { setupRequired } }";
        assertThat(post(TestKeys.sign(other, claims), q, Map.of(), "SetupStatus", Map.of()).status()).isEqualTo(401);
        var unknownKid = TestKeys.generate("rotated-away");
        assertThat(post(TestKeys.sign(unknownKid, claims), q, Map.of(), "SetupStatus", Map.of()).status()).isEqualTo(401);

        String good = TestKeys.sign(KEY, claims);
        String[] parts = good.split("\\.");
        String tampered = parts[0] + "." + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                "{\"iss\":\"paybridge-console\",\"aud\":\"paybridge-core\",\"op\":\"SetupStatus\"}".getBytes()) + "." + parts[2];
        assertThat(post(tampered, q, Map.of(), "SetupStatus", Map.of()).status()).isEqualTo(401);
    }

    @Test
    void noneAndHmacAlgorithmsAreRefused() {
        var claims = TestKeys.claims(null, null, "SetupStatus", Instant.now().getEpochSecond());
        var q = "query SetupStatus { setupStatus { setupRequired } }";
        assertThat(post(TestKeys.unsigned(Map.of("alg", "none", "typ", "JWT"), claims), q, Map.of(), "SetupStatus", Map.of()).status()).isEqualTo(401);
        assertThat(post(TestKeys.unsigned(Map.of("alg", "HS256", "typ", "JWT", "kid", "console-test-1"), claims), q, Map.of(), "SetupStatus", Map.of()).status()).isEqualTo(401);
    }

    @Test
    void expiredAndNotYetValidTokensAreRefusedButSkewIsTolerated() {
        long now = Instant.now().getEpochSecond();
        var q = "query SetupStatus { setupStatus { setupRequired } }";
        assertThat(withClaims(TestKeys.claims(null, null, "SetupStatus", now - 60), q, "SetupStatus").status()).as("expired").isEqualTo(401);
        assertThat(withClaims(TestKeys.claims(null, null, "SetupStatus", now + 60), q, "SetupStatus").status()).as("future").isEqualTo(401);
        assertThat(withClaims(TestKeys.claims(null, null, "SetupStatus", now - 33), q, "SetupStatus").status()).as("within 5s skew after exp").isEqualTo(200);
        assertThat(withClaims(TestKeys.claims(null, null, "SetupStatus", now + 3), q, "SetupStatus").status()).as("within 5s skew before nbf").isEqualTo(200);
    }

    @Test
    void theOperationNameInTheDocumentMustMatchTheSignedOperation() {
        var q = "query SetupStatus { setupStatus { setupRequired } }";
        var claims = TestKeys.claims(null, null, "Me", Instant.now().getEpochSecond());
        assertThat(withClaims(claims, q, "SetupStatus").status()).as("token authorizes Me, document is SetupStatus").isEqualTo(401);
    }

    @Test
    void aDocumentWithTwoOperationsIsRefused() {
        String doc = "query SetupStatus { setupStatus { setupRequired } } query Other { setupStatus { setupRequired } }";
        var claims = TestKeys.claims(null, null, "SetupStatus", Instant.now().getEpochSecond());
        assertThat(withClaims(claims, doc, "SetupStatus").status()).isEqualTo(401);
        assertThat(withClaims(claims, "{ setupStatus { setupRequired } }", null).status()).as("anonymous operation").isEqualTo(401);
    }

    @Test
    void anAnonymousTokenCannotUseAProtectedOperation() {
        var r = call(null, null, "Invoices", INVOICES, Map.of());
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.data().path("invoices").path("code").asText()).isEqualTo("UNAUTHENTICATED");
        assertThat(call(null, null, "Me", ME, Map.of()).data().path("me").isNull()).isTrue();
    }

    @Test
    void aTokenWithTooLongALifetimeIsRefused() {
        var claims = TestKeys.claims(null, null, "SetupStatus", Instant.now().getEpochSecond());
        claims.put("exp", (long) claims.get("iat") + 600);
        assertThat(withClaims(claims, "query SetupStatus { setupStatus { setupRequired } }", "SetupStatus").status()).isEqualTo(401);
    }

    @Test
    void aValidSignedRequestWorksAndIntrospectionIsOff() {
        user("target.user", GlobalRole.USER, false);
        var ok = post(token(null, null, "SetupStatus"), "query SetupStatus { setupStatus { setupRequired } }", Map.of(), "SetupStatus", Map.of());
        assertThat(ok.status()).isEqualTo(200);
        var intro = post(token(null, null, "Introspect"), "query Introspect { __schema { types { name } } }", Map.of(), "Introspect", Map.of());
        assertThat(intro.body().path("data").path("__schema").isMissingNode() || intro.body().path("data").path("__schema").isNull()
                || intro.body().has("errors")).isTrue();
    }
}
