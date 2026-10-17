package com.paybridge.core.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.paybridge.core.security.ServiceContextVerifier.Reason;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServiceContextVerifierTest {
    private static final Instant NOW = Instant.parse("2026-10-17T03:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final long T = NOW.getEpochSecond();

    private static final TestJwt.Key CURRENT = TestJwt.key("console-2026-10");
    private static final TestJwt.Key PREVIOUS = TestJwt.key("console-2026-09");

    @TempDir Path dir;
    private Path jwks;
    private ServiceContextVerifier verifier;

    @BeforeEach
    void setUp() throws IOException {
        jwks = dir.resolve("jwks.json");
        TestJwt.writeJwks(jwks, List.of(CURRENT, PREVIOUS));
        verifier = new ServiceContextVerifier(new JwksKeyProvider(jwks, CLOCK), CLOCK, Set.of());
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private Reason reasonOf(String header) {
        return org.junit.jupiter.api.Assertions.assertThrows(
                ServiceContextVerifier.Rejected.class, () -> verifier.verify(header)).reason();
    }

    @Test
    void acceptsAWellFormedToken() {
        var claims = verifier.verify(bearer(TestJwt.sign(CURRENT, TestJwt.claims(T))));
        assertThat(claims.userId()).isEqualTo("00000000-0000-4000-8000-000000000501");
        assertThat(claims.merchantId()).isEqualTo("00000000-0000-4000-8000-000000000301");
        assertThat(claims.operation()).isEqualTo("RequestPayment");
        assertThat(claims.requestId()).isEqualTo("demo-run-001");
    }

    @Test
    void anonymousOperationsMayOmitSubAndMid() {
        var c = TestJwt.claims(T);
        c.remove("sub");
        c.remove("mid");
        var claims = verifier.verify(bearer(TestJwt.sign(CURRENT, c)));
        assertThat(claims.userId()).isNull();
        assertThat(claims.merchantId()).isNull();
    }

    @Test
    void aTokenSignedWithThePreviousKeyStillWorksDuringRotation() {
        assertThat(verifier.verify(bearer(TestJwt.sign(PREVIOUS, TestJwt.claims(T)))).operation()).isEqualTo("RequestPayment");
    }

    @Test
    void afterTheOldKeyIsRetiredItsTokensAreRefusedWithoutARestart() throws Exception {
        var now = new java.util.concurrent.atomic.AtomicReference<>(NOW);
        Clock moving = new Clock() {
            @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return now.get(); }
        };
        var v = new ServiceContextVerifier(new JwksKeyProvider(jwks, moving), moving, Set.of());
        assertThat(v.verify(bearer(TestJwt.sign(PREVIOUS, TestJwt.claims(T)))).operation()).isEqualTo("RequestPayment");

        TestJwt.writeJwks(jwks, List.of(CURRENT));        // the Secret is rotated: only the new key remains
        Files.setLastModifiedTime(jwks, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 5000));
        now.set(NOW.plusSeconds(10));
        assertThatThrownBy(() -> v.verify(bearer(TestJwt.sign(PREVIOUS, TestJwt.claims(T + 10)))))
                .extracting(e -> ((ServiceContextVerifier.Rejected) e).reason()).isEqualTo(Reason.UNKNOWN_KEY);
        assertThat(v.verify(bearer(TestJwt.sign(CURRENT, TestJwt.claims(T + 10)))).operation()).isEqualTo("RequestPayment");
    }

    @Test
    void rejectsMissingAndMalformedHeaders() {
        assertThat(reasonOf(null)).isEqualTo(Reason.MISSING);
        assertThat(reasonOf("Basic abc")).isEqualTo(Reason.MISSING);
        assertThat(reasonOf("Bearer ")).isEqualTo(Reason.MALFORMED);
        assertThat(reasonOf("Bearer a.b")).isEqualTo(Reason.MALFORMED);
        assertThat(reasonOf("Bearer a.b.c.d")).isEqualTo(Reason.MALFORMED);
        assertThat(reasonOf("Bearer " + "x".repeat(5000))).isEqualTo(Reason.MALFORMED);
        assertThat(reasonOf("Bearer ###.###.###")).isEqualTo(Reason.MALFORMED);
    }

    @Test
    void rejectsEveryAlgorithmExceptRs256() {
        for (String alg : List.of("none", "HS256", "HS512", "RS512", "ES256", "PS256", "rs256")) {
            String token = TestJwt.sign(CURRENT, Map.of("alg", alg, "typ", "JWT", "kid", "console-2026-10"), TestJwt.claims(T));
            assertThat(reasonOf(bearer(token))).as(alg).isEqualTo(Reason.ALGORITHM);
        }
        String noType = TestJwt.sign(CURRENT, Map.of("alg", "RS256", "kid", "console-2026-10"), TestJwt.claims(T));
        assertThat(reasonOf(bearer(noType))).isEqualTo(Reason.ALGORITHM);
    }

    @Test
    void rejectsUnknownOrMissingKid() {
        var rogue = TestJwt.key("rogue");
        assertThat(reasonOf(bearer(TestJwt.sign(rogue, TestJwt.claims(T))))).isEqualTo(Reason.UNKNOWN_KEY);
        String noKid = TestJwt.sign(CURRENT, Map.of("alg", "RS256", "typ", "JWT"), TestJwt.claims(T));
        assertThat(reasonOf(bearer(noKid))).isEqualTo(Reason.UNKNOWN_KEY);
    }

    @Test
    void rejectsABadSignatureWithAKnownKid() {
        var impostor = new TestJwt.Key("console-2026-10", TestJwt.key("x").pair());
        assertThat(reasonOf(bearer(TestJwt.sign(impostor, TestJwt.claims(T))))).isEqualTo(Reason.SIGNATURE);
        String good = TestJwt.sign(CURRENT, TestJwt.claims(T));
        String[] p = good.split("\\.");
        String swapped = p[0] + "." + TestJwt.b64(TestJwt.json(Map.of("iss", "paybridge-console", "aud", "paybridge-core",
                "op", "Evil", "rid", "r", "jti", UUID_OK, "iat", T, "nbf", T, "exp", T + 30))) + "." + p[2];
        assertThat(reasonOf(bearer(swapped))).isEqualTo(Reason.SIGNATURE);
    }

    private static final String UUID_OK = "6f8e0c0a-2a0b-4a77-b7c2-9d6c2b6f1d42";

    @Test
    void rejectsWrongIssuerAndAudience() {
        var c = TestJwt.claims(T);
        c.put("iss", "someone-else");
        assertThat(reasonOf(bearer(TestJwt.sign(CURRENT, c)))).isEqualTo(Reason.ISSUER);
        c = TestJwt.claims(T);
        c.put("aud", "paybridge-console");
        assertThat(reasonOf(bearer(TestJwt.sign(CURRENT, c)))).isEqualTo(Reason.AUDIENCE);
        c = TestJwt.claims(T);
        c.put("aud", List.of("paybridge-core"));
        assertThat(reasonOf(bearer(TestJwt.sign(CURRENT, c)))).as("an array audience is not accepted").isEqualTo(Reason.AUDIENCE);
    }

    @Test
    void enforcesTimeWindowsWithFiveSecondsOfSkew() {
        assertThat(verifier.verify(bearer(TestJwt.sign(CURRENT, TestJwt.claims(T - 30 - 5)))).operation()).as("exp = now - 5").isNotNull();
        assertThat(reasonOf(bearer(TestJwt.sign(CURRENT, TestJwt.claims(T - 30 - 6))))).as("exp = now - 6").isEqualTo(Reason.EXPIRED);
        assertThat(verifier.verify(bearer(TestJwt.sign(CURRENT, TestJwt.claims(T + 5)))).operation()).as("nbf = now + 5").isNotNull();
        assertThat(reasonOf(bearer(TestJwt.sign(CURRENT, TestJwt.claims(T + 6))))).as("nbf = now + 6").isEqualTo(Reason.NOT_YET_VALID);
    }

    @Test
    void enforcesTheThirtySecondLifetime() {
        var c = TestJwt.claims(T);
        c.put("exp", T + 31);
        assertThat(reasonOf(bearer(TestJwt.sign(CURRENT, c)))).isEqualTo(Reason.LIFETIME);
        c.put("exp", T);
        assertThat(reasonOf(bearer(TestJwt.sign(CURRENT, c)))).isEqualTo(Reason.LIFETIME);
        c.put("exp", T + 30);
        assertThat(verifier.verify(bearer(TestJwt.sign(CURRENT, c)))).isNotNull();
    }

    @Test
    void rejectsAnyClaimOutsideTheContractIncludingRolesAndPermissions() {
        for (String forbidden : List.of("role", "roles", "scope", "admin", "permissions", "merchants", "email", "x")) {
            var c = TestJwt.claims(T);
            c.put(forbidden, forbidden.equals("admin") ? true : "value");
            assertThat(reasonOf(bearer(TestJwt.sign(CURRENT, c)))).as(forbidden).isEqualTo(Reason.FORBIDDEN_CLAIM);
        }
    }

    @Test
    void rejectsMissingOrInvalidRequiredClaims() {
        for (String required : List.of("op", "rid", "jti", "iat", "nbf", "exp")) {
            var c = TestJwt.claims(T);
            c.remove(required);
            assertThat(reasonOf(bearer(TestJwt.sign(CURRENT, c)))).as("missing " + required).isEqualTo(Reason.CLAIM);
        }
        var c = TestJwt.claims(T);
        c.put("sub", "not-a-uuid");
        assertThat(reasonOf(bearer(TestJwt.sign(CURRENT, c)))).isEqualTo(Reason.CLAIM);
        c = TestJwt.claims(T);
        c.put("op", "bad operation name");
        assertThat(reasonOf(bearer(TestJwt.sign(CURRENT, c)))).isEqualTo(Reason.CLAIM);
        c = TestJwt.claims(T);
        c.put("exp", "soon");
        assertThat(reasonOf(bearer(TestJwt.sign(CURRENT, c)))).isEqualTo(Reason.CLAIM);
    }

    @Test
    void enforcesTheOperationAllowListWhenConfigured() {
        var strict = new ServiceContextVerifier(new JwksKeyProvider(jwks, CLOCK), CLOCK, Set.of("RequestPayment", "Me"));
        assertThat(strict.verify(bearer(TestJwt.sign(CURRENT, TestJwt.claims(T)))).operation()).isEqualTo("RequestPayment");
        var c = TestJwt.claims(T);
        c.put("op", "DropEverything");
        assertThatThrownBy(() -> strict.verify(bearer(TestJwt.sign(CURRENT, c))))
                .extracting(e -> ((ServiceContextVerifier.Rejected) e).reason()).isEqualTo(Reason.OPERATION_NOT_ALLOWED);
    }

    @Test
    void rejectionNeverCarriesTokenContent() {
        String token = TestJwt.sign(CURRENT, TestJwt.claims(T - 1000));
        assertThatThrownBy(() -> verifier.verify(bearer(token))).hasMessage("EXPIRED").hasNoCause();
    }

    @Test
    void weakKeysAreIgnoredAndAnEmptyProviderRejectsEverything() throws Exception {
        var weak = TestJwt.key("weak", 1024);
        Path weakFile = dir.resolve("weak.json");
        TestJwt.writeJwks(weakFile, List.of(weak));
        var v = new ServiceContextVerifier(new JwksKeyProvider(weakFile, CLOCK), CLOCK, Set.of());
        assertThat(reasonOfWith(v, bearer(TestJwt.sign(weak, TestJwt.claims(T))))).isEqualTo(Reason.UNKNOWN_KEY);
        var missing = new ServiceContextVerifier(new JwksKeyProvider(dir.resolve("absent.json"), CLOCK), CLOCK, Set.of());
        assertThat(reasonOfWith(missing, bearer(TestJwt.sign(CURRENT, TestJwt.claims(T))))).isEqualTo(Reason.UNKNOWN_KEY);
    }

    private static Reason reasonOfWith(ServiceContextVerifier v, String header) {
        return org.junit.jupiter.api.Assertions.assertThrows(
                ServiceContextVerifier.Rejected.class, () -> v.verify(header)).reason();
    }
}
