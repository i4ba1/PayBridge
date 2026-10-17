package com.paybridge.adapter.provider;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paybridge.adapter.provider.ProviderResponseClassifier.Expectation;
import com.paybridge.application.port.BillingProvider;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;

/**
 * {@link BillingProvider} for the PayBridge provider simulator, over its REST contract (API contract section 8).
 *
 * <p><b>The simulator is not a real provider.</b> It keeps every idempotency key for the life of its dataset, assigns
 * revisions itself and answers lookups from the same table it charges from. A real provider may expire idempotency
 * keys, deliver events out of order or answer a lookup late, so a real adapter needs its own contract tests and its
 * own runbook (docs/runbooks/provider-integration.md, FR-031). Nothing here should be copied to production code
 * without that review.
 *
 * <p>The adapter performs no SQL, sends the same provider key on every attempt, and never decides from the HTTP status
 * alone: see {@link ProviderResponseClassifier}.
 */
public class SimulatorBillingProvider implements BillingProvider {
    public static final Duration DEFAULT_CALL_TIMEOUT = Duration.ofSeconds(10);
    public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_BODY_BYTES = 64 * 1024;

    private final URI baseUri;
    private final String bearerToken;
    private final Duration callTimeout;
    private final HttpClient http;

    public SimulatorBillingProvider(URI baseUri, String bearerToken) {
        this(baseUri, bearerToken, DEFAULT_CONNECT_TIMEOUT, DEFAULT_CALL_TIMEOUT);
    }

    public SimulatorBillingProvider(URI baseUri, String bearerToken, Duration connectTimeout, Duration callTimeout) {
        this.baseUri = baseUri;
        this.bearerToken = bearerToken;
        this.callTimeout = callTimeout;
        this.http = HttpClient.newBuilder().connectTimeout(connectTimeout).followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1).build();
    }

    @Override
    public ProviderResult charge(ChargeRequest request) {
        String body;
        try {
            var fields = new LinkedHashMap<String, Object>();
            fields.put("paymentId", request.paymentId());
            fields.put("merchantId", request.merchantId());
            fields.put("invoiceId", request.invoiceId());
            fields.put("amountMinor", Long.toString(request.amountMinor()));
            fields.put("currency", request.currency());
            fields.put("methodToken", request.methodToken());
            fields.put("scenarioRunId", request.scenarioRunId() == null ? "default" : request.scenarioRunId());
            body = JSON.writeValueAsString(fields);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot encode the charge request", e);
        }
        var builder = base("/v1/charges", request.correlationId())
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", request.providerKey())
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        var expectation = new Expectation(request.providerKey(), request.amountMinor(), request.currency(), null);
        return exchange(builder.build(), expectation, false);
    }

    @Override
    public ProviderResult lookup(LookupRequest request) {
        var builder = base("/v1/charges/by-key/" + request.providerKey(), request.correlationId()).GET();
        var expectation = new Expectation(request.providerKey(), request.expectedAmountMinor(), request.expectedCurrency(),
                request.highestKnownRevision());
        return exchange(builder.build(), expectation, true);
    }

    private HttpRequest.Builder base(String path, String correlationId) {
        var b = HttpRequest.newBuilder(baseUri.resolve(path)).timeout(callTimeout)
                .header("Authorization", "Bearer " + bearerToken).header("Accept", "application/json");
        if (correlationId != null && correlationId.matches("^[A-Za-z0-9._:-]{1,64}$")) {
            b.header("X-Correlation-Id", correlationId);
        }
        return b;
    }

    private ProviderResult exchange(HttpRequest request, Expectation expected, boolean lookup) {
        try {
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            byte[] raw = response.body();
            if (raw != null && raw.length > MAX_BODY_BYTES) {
                return ProviderResult.noEvidence(ErrorClass.MALFORMED, null, response.statusCode(), "body too large");
            }
            String text = raw == null ? "" : new String(raw, StandardCharsets.UTF_8);
            String retryAfter = response.headers().firstValue("Retry-After").orElse(null);
            return lookup
                    ? ProviderResponseClassifier.classifyLookup(response.statusCode(), text, retryAfter, expected)
                    : ProviderResponseClassifier.classifyCharge(response.statusCode(), text, retryAfter, expected);
        } catch (IOException e) {
            return ProviderResponseClassifier.classifyFailure(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ProviderResponseClassifier.classifyFailure(e);
        }
    }

    /** Test helper: describes the configured endpoint without the token. */
    @Override
    public String toString() {
        return "SimulatorBillingProvider[" + baseUri + "]";
    }

}
