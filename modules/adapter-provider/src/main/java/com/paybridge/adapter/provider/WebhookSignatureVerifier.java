package com.paybridge.adapter.provider;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Verifies the simulator's webhook signature (API contract 8.4): {@code v1=} plus lower case hex of
 * HMAC-SHA256(secret, timestamp + "." + rawBody) over the exact received bytes. Two secrets are tried during rotation.
 * Comparison is constant time and the timestamp must be within five minutes. The reason for a rejection is for logs
 * only; callers answer 401 with an empty body.
 */
public final class WebhookSignatureVerifier {
    public static final long WINDOW_SECONDS = 300;
    private static final String PREFIX = "v1=";

    public enum Result { VALID, MISSING, MALFORMED, STALE, BAD_SIGNATURE }

    private final List<byte[]> secrets;
    private final Clock clock;

    /** Current secret first; an optional previous secret second. Blank secrets are ignored. */
    public WebhookSignatureVerifier(List<String> secrets, Clock clock) {
        this.secrets = secrets.stream().filter(s -> s != null && !s.isBlank())
                .map(s -> s.getBytes(StandardCharsets.UTF_8)).toList();
        this.clock = clock;
        if (this.secrets.isEmpty()) {
            throw new IllegalArgumentException("at least one webhook secret is required");
        }
    }

    public Result verify(String timestampHeader, String signatureHeader, byte[] rawBody) {
        if (timestampHeader == null || signatureHeader == null || rawBody == null) {
            return Result.MISSING;
        }
        long timestamp;
        try {
            timestamp = Long.parseLong(timestampHeader.strip());
        } catch (NumberFormatException e) {
            return Result.MALFORMED;
        }
        if (!signatureHeader.startsWith(PREFIX)) {
            return Result.MALFORMED;
        }
        byte[] presented;
        try {
            presented = HexFormat.of().parseHex(signatureHeader.substring(PREFIX.length()));
        } catch (IllegalArgumentException e) {
            return Result.MALFORMED;
        }
        if (Math.abs(clock.millis() / 1000 - timestamp) > WINDOW_SECONDS) {
            return Result.STALE;
        }
        boolean match = false;
        for (byte[] secret : secrets) {
            // Every secret is checked and results are combined, so timing does not reveal which one matched.
            match |= MessageDigest.isEqual(presented, hmac(secret, timestamp, rawBody));
        }
        return match ? Result.VALID : Result.BAD_SIGNATURE;
    }

    /** The signature header value a sender would produce. Used by tests and by senders that share this code. */
    public static String sign(String secret, long timestamp, byte[] rawBody) {
        return PREFIX + HexFormat.of().formatHex(hmac(secret.getBytes(StandardCharsets.UTF_8), timestamp, rawBody));
    }

    private static byte[] hmac(byte[] secret, long timestamp, byte[] rawBody) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            mac.update((timestamp + ".").getBytes(StandardCharsets.US_ASCII));
            return mac.doFinal(rawBody);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }
}
