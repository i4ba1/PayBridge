package com.paybridge.adapter.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paybridge.application.port.BillingProvider.ErrorClass;
import com.paybridge.application.port.BillingProvider.ProviderResult;
import com.paybridge.application.port.BillingProvider.ProviderResult.Kind;
import com.paybridge.domain.payment.ProviderObservation;
import com.paybridge.domain.payment.ProviderOutcome;
import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Optional;

/**
 * Turns one HTTP exchange into exactly one {@link ProviderResult} (API contract 8.5). HTTP status alone never decides:
 * a 2xx body is only evidence when every field agrees with what was asked. Anything that does not add up is
 * MALFORMED, which the caller treats as transport uncertainty and never as success or decline.
 */
public final class ProviderResponseClassifier {
    static final Duration MAX_RETRY_AFTER = Duration.ofMinutes(5);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** What the caller expects the answer to be about. {@code knownRevision} may be null. */
    public record Expectation(String providerKey, long amountMinor, String currency, Long knownRevision) {}

    private ProviderResponseClassifier() {}

    /** @param retryAfterHeader the raw Retry-After value, or null */
    public static ProviderResult classifyCharge(int status, String body, String retryAfterHeader, Expectation expected) {
        return classify(status, body, retryAfterHeader, expected, false);
    }

    public static ProviderResult classifyLookup(int status, String body, String retryAfterHeader, Expectation expected) {
        return classify(status, body, retryAfterHeader, expected, true);
    }

    private static ProviderResult classify(int status, String body, String retryAfter, Expectation expected, boolean lookup) {
        switch (status) {
            case 200, 201, 202 -> {
                return success(status, body, expected, lookup);
            }
            case 409 -> {
                return "idempotency_conflict".equals(errorCode(body)) && !lookup
                        ? ProviderResult.of(Kind.IDEMPOTENCY_CONFLICT, status, "idempotency_conflict")
                        : malformed(status, "unexpected 409");
            }
            case 422 -> {
                return "SCENARIO_NOT_AVAILABLE".equals(errorCode(body)) && !lookup
                        ? ProviderResult.of(Kind.SCENARIO_NOT_AVAILABLE, status, "scenario run not available")
                        : ProviderResult.of(Kind.CLIENT_ERROR, status, "unprocessable");
            }
            case 404 -> {
                return lookup && "charge_not_found".equals(errorCode(body))
                        ? ProviderResult.of(Kind.NOT_FOUND, status, "charge_not_found")
                        : lookup ? malformed(status, "unexpected 404") : ProviderResult.of(Kind.CLIENT_ERROR, status, "not found");
            }
            case 429, 503 -> {
                return ProviderResult.noEvidence(ErrorClass.RETRYABLE_UNAVAILABLE, parseRetryAfter(retryAfter), status,
                        "provider unavailable");
            }
            default -> {
                if (status == 400 || status == 401 || status == 403) {
                    return ProviderResult.of(Kind.CLIENT_ERROR, status, errorCode(body) == null ? "client error" : errorCode(body));
                }
                if (status >= 500) {
                    return ProviderResult.noEvidence(ErrorClass.TRANSPORT_UNCERTAIN, null, status, "server error");
                }
                return malformed(status, "unexpected status");
            }
        }
    }

    /** An exception during the exchange. Whether the request reached the provider decides the class. */
    public static ProviderResult classifyFailure(Throwable t) {
        if (t instanceof HttpConnectTimeoutException || t instanceof ConnectException) {
            // Nothing was sent: the provider cannot have acted, so the same key is safe to try again.
            return ProviderResult.noEvidence(ErrorClass.RETRYABLE_UNAVAILABLE, null, null, "could not connect");
        }
        if (t instanceof HttpTimeoutException) {
            return ProviderResult.noEvidence(ErrorClass.TRANSPORT_UNCERTAIN, null, null, "timeout");
        }
        if (t instanceof IOException) {
            return ProviderResult.noEvidence(ErrorClass.TRANSPORT_UNCERTAIN, null, null, "connection lost");
        }
        return ProviderResult.noEvidence(ErrorClass.TRANSPORT_UNCERTAIN, null, null, "interrupted or failed");
    }

    private static ProviderResult success(int status, String body, Expectation expected, boolean lookup) {
        JsonNode n = parse(body);
        if (n == null || !n.isObject()) {
            return malformed(status, "body is not a JSON object");
        }
        String key = text(n, "providerKey");
        String reference = text(n, "reference");
        String st = text(n, "status");
        String amount = text(n, "amountMinor");
        String currency = text(n, "currency");
        JsonNode revisionNode = n.get("revision");
        if (key == null || reference == null || st == null || amount == null || currency == null
                || revisionNode == null || !revisionNode.isIntegralNumber() || revisionNode.asLong() < 1) {
            return malformed(status, "required field missing or wrongly typed");
        }
        if (!key.equals(expected.providerKey())) {
            return malformed(status, "providerKey differs");
        }
        if (!amount.equals(Long.toString(expected.amountMinor())) || !currency.equals(expected.currency())) {
            return malformed(status, "amount or currency differs");
        }
        long revision = revisionNode.asLong();
        if (expected.knownRevision() != null && revision < expected.knownRevision()) {
            return malformed(status, "revision is lower than already stored");
        }
        Optional<ProviderOutcome> outcome = switch (st) {
            case "SUCCEEDED" -> Optional.of(ProviderOutcome.SUCCEEDED);
            case "DECLINED" -> Optional.of(ProviderOutcome.DECLINED);
            case "PENDING" -> Optional.of(ProviderOutcome.PENDING);
            default -> Optional.empty();
        };
        if (outcome.isEmpty()) {
            return malformed(status, "unknown status");
        }
        if (status == 202 && outcome.get() != ProviderOutcome.PENDING) {
            return malformed(status, "202 must carry PENDING");
        }
        String decline = null;
        if (outcome.get() == ProviderOutcome.DECLINED) {
            decline = text(n, "declineCode");
            decline = decline == null ? "UNSPECIFIED" : decline;
        } else if (n.hasNonNull("declineCode")) {
            return malformed(status, "declineCode on a non decline");
        }
        return ProviderResult.observed(new ProviderObservation(outcome.get(), revision, reference), decline, status);
    }

    private static ProviderResult malformed(int status, String detail) {
        return ProviderResult.noEvidence(ErrorClass.MALFORMED, null, status, detail);
    }

    private static JsonNode parse(String body) {
        try {
            return body == null || body.isBlank() ? null : JSON.readTree(body);
        } catch (IOException e) {
            return null;
        }
    }

    private static String errorCode(String body) {
        JsonNode n = parse(body);
        return n == null ? null : text(n.path("error"), "code");
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v != null && v.isTextual() ? v.asText() : null;
    }

    /** Seconds only (the simulator contract); a larger value is capped, junk is ignored. */
    static Duration parseRetryAfter(String header) {
        if (header == null) {
            return null;
        }
        try {
            long seconds = Long.parseLong(header.strip());
            if (seconds < 0) {
                return null;
            }
            Duration d = Duration.ofSeconds(seconds);
            return d.compareTo(MAX_RETRY_AFTER) > 0 ? MAX_RETRY_AFTER : d;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
