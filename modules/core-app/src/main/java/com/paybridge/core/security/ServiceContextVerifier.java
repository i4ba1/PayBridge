package com.paybridge.core.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Signature;
import java.time.Clock;
import java.util.Base64;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Verifies the console's 30 second RS256 service context (API contract section 7). The token says who is calling and
 * for which single operation; it never carries a role or permission, and a token that tries to is rejected.
 */
public class ServiceContextVerifier {
    public static final String ISSUER = "paybridge-console";
    public static final String AUDIENCE = "paybridge-core";
    public static final long LIFETIME_SECONDS = 30;
    public static final long SKEW_SECONDS = 5;

    private static final Set<String> ALLOWED_CLAIMS = Set.of("iss", "aud", "sub", "mid", "op", "rid", "jti", "iat", "nbf", "exp");
    private static final Pattern UUID = Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final Pattern OPERATION = Pattern.compile("^[A-Za-z][A-Za-z0-9]{0,63}$");
    private static final Pattern REQUEST_ID = Pattern.compile("^[A-Za-z0-9._:-]{1,64}$");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_TOKEN_LENGTH = 4096;

    public enum Reason {
        MISSING, MALFORMED, ALGORITHM, UNKNOWN_KEY, SIGNATURE, ISSUER, AUDIENCE, NOT_YET_VALID, EXPIRED, LIFETIME,
        FORBIDDEN_CLAIM, CLAIM, OPERATION_NOT_ALLOWED
    }

    /** The reason is for logs and a security counter; the caller sees a generic failure only. */
    public static final class Rejected extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final Reason reason;

        public Rejected(Reason reason) {
            super(reason.name(), null, false, false);
            this.reason = reason;
        }

        public Reason reason() {
            return reason;
        }
    }

    /** userId and merchantId are null when the claim is absent. */
    public record Claims(String userId, String merchantId, String operation, String requestId, String tokenId) {}

    private final JwksKeyProvider keys;
    private final Clock clock;
    private final Set<String> allowedOperations;

    /** An empty allow-list disables the check (local and CI profiles); hosted profiles pass the generated operation set. */
    public ServiceContextVerifier(JwksKeyProvider keys, Clock clock, Set<String> allowedOperations) {
        this.keys = keys;
        this.clock = clock;
        this.allowedOperations = Set.copyOf(allowedOperations);
    }

    public Claims verify(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            throw new Rejected(Reason.MISSING);
        }
        String token = authorizationHeader.substring(7).strip();
        if (token.isEmpty() || token.length() > MAX_TOKEN_LENGTH) {
            throw new Rejected(Reason.MALFORMED);
        }
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3 || parts[0].isEmpty() || parts[1].isEmpty() || parts[2].isEmpty()) {
            throw new Rejected(Reason.MALFORMED);
        }
        JsonNode header = json(parts[0]);
        if (!"RS256".equals(text(header, "alg")) || !"JWT".equals(text(header, "typ"))) {
            throw new Rejected(Reason.ALGORITHM);
        }
        String kid = text(header, "kid");
        if (kid == null || kid.isEmpty()) {
            throw new Rejected(Reason.UNKNOWN_KEY);
        }
        var key = keys.key(kid).orElseThrow(() -> new Rejected(Reason.UNKNOWN_KEY));
        try {
            var sig = Signature.getInstance("SHA256withRSA");
            sig.initVerify(key);
            sig.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            if (!sig.verify(decode(parts[2]))) {
                throw new Rejected(Reason.SIGNATURE);
            }
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new Rejected(Reason.SIGNATURE);
        }

        JsonNode claims = json(parts[1]);
        claims.fieldNames().forEachRemaining(name -> {
            if (!ALLOWED_CLAIMS.contains(name)) {
                throw new Rejected(Reason.FORBIDDEN_CLAIM);
            }
        });
        if (!ISSUER.equals(text(claims, "iss"))) {
            throw new Rejected(Reason.ISSUER);
        }
        if (!AUDIENCE.equals(text(claims, "aud"))) {
            throw new Rejected(Reason.AUDIENCE);
        }
        long now = clock.millis() / 1000;
        long iat = number(claims, "iat"), nbf = number(claims, "nbf"), exp = number(claims, "exp");
        if (nbf > now + SKEW_SECONDS || iat > now + SKEW_SECONDS) {
            throw new Rejected(Reason.NOT_YET_VALID);
        }
        if (exp < now - SKEW_SECONDS) {
            throw new Rejected(Reason.EXPIRED);
        }
        if (exp - iat > LIFETIME_SECONDS || exp <= iat) {
            throw new Rejected(Reason.LIFETIME);
        }
        String op = text(claims, "op");
        String rid = text(claims, "rid");
        String jti = text(claims, "jti");
        String sub = text(claims, "sub");
        String mid = text(claims, "mid");
        if (op == null || !OPERATION.matcher(op).matches() || rid == null || !REQUEST_ID.matcher(rid).matches()
                || jti == null || !UUID.matcher(jti).matches()
                || (claims.has("sub") && (sub == null || !UUID.matcher(sub).matches()))
                || (claims.has("mid") && (mid == null || !UUID.matcher(mid).matches()))) {
            throw new Rejected(Reason.CLAIM);
        }
        if (!allowedOperations.isEmpty() && !allowedOperations.contains(op)) {
            throw new Rejected(Reason.OPERATION_NOT_ALLOWED);
        }
        return new Claims(sub, mid, op, rid, jti);
    }

    private static JsonNode json(String part) {
        try {
            JsonNode node = JSON.readTree(decode(part));
            if (node == null || !node.isObject()) {
                throw new Rejected(Reason.MALFORMED);
            }
            return node;
        } catch (java.io.IOException | IllegalArgumentException e) {
            throw new Rejected(Reason.MALFORMED);
        }
    }

    private static byte[] decode(String s) {
        return Base64.getUrlDecoder().decode(s);
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v != null && v.isTextual() ? v.asText() : null;
    }

    private static long number(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || !v.isIntegralNumber()) {
            throw new Rejected(Reason.CLAIM);
        }
        return v.asLong();
    }
}
