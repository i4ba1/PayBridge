package com.paybridge.simulator.service;

import java.util.Set;
import java.util.regex.Pattern;

/** POST /v1/charges body. Every field is immutable for the idempotency key. */
public record ChargeBody(String paymentId, String merchantId, String invoiceId, String amountMinor, String currency,
                         String methodToken, String scenarioRunId) {
    /** The three synthetic tokens of the seed (D-29). They never contain card data. */
    public static final Set<String> TOKENS = Set.of("tok_sim_card_default", "tok_sim_card_alt", "tok_sim_transfer_default");
    private static final Pattern UUID = Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final Pattern AMOUNT = Pattern.compile("^[1-9][0-9]{0,17}$");
    public static final Pattern RUN_ID = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");

    public ChargeBody withValidatedDefaults(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isEmpty() || idempotencyKey.length() > 64
                || !idempotencyKey.chars().allMatch(c -> c > 31 && c < 127)) {
            throw ApiException.invalid("Idempotency-Key must be 1 to 64 printable ASCII characters");
        }
        require(paymentId != null && UUID.matcher(paymentId).matches(), "paymentId must be a UUID");
        require(idempotencyKey.equals("pbk-" + paymentId), "Idempotency-Key must equal pbk-<paymentId>");
        require(merchantId != null && UUID.matcher(merchantId).matches(), "merchantId must be a UUID");
        require(invoiceId != null && UUID.matcher(invoiceId).matches(), "invoiceId must be a UUID");
        require(amountMinor != null && AMOUNT.matcher(amountMinor).matches(), "amountMinor must be a positive integer string");
        require("IDR".equals(currency) || "USD".equals(currency), "currency must be IDR or USD");
        require(methodToken != null && TOKENS.contains(methodToken), "methodToken is not a known synthetic token");
        String run = scenarioRunId == null ? "default" : scenarioRunId;
        require(RUN_ID.matcher(run).matches(), "scenarioRunId has an invalid format");
        return new ChargeBody(paymentId.toLowerCase(), merchantId.toLowerCase(), invoiceId.toLowerCase(), amountMinor, currency,
                methodToken, run);
    }

    private static void require(boolean ok, String message) {
        if (!ok) {
            throw ApiException.invalid(message);
        }
    }

    /** SHA-256 over the immutable fields in a fixed order, so the same request always hashes the same. */
    public String requestHash() {
        String canonical = String.join("\n", "amountMinor=" + amountMinor, "currency=" + currency, "invoiceId=" + invoiceId,
                "merchantId=" + merchantId, "methodToken=" + methodToken, "paymentId=" + paymentId,
                "scenarioRunId=" + scenarioRunId);
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
