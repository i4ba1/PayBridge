package com.paybridge.adapter.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paybridge.application.port.BillingProvider.ChargeRequest;
import com.paybridge.application.port.BillingProvider.ErrorClass;
import com.paybridge.application.port.BillingProvider.LookupRequest;
import com.paybridge.application.port.BillingProvider.ProviderResult.Kind;
import com.paybridge.domain.payment.ProviderOutcome;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The request the adapter sends, and the way it reacts to a real socket misbehaving, against a stub that speaks the
 * simulator's HTTP contract. The real simulator is exercised in the integration tests.
 */
class ProviderContractTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PAYMENT = "00000000-0000-4000-8000-000000000201";
    private static final String KEY = "pbk-" + PAYMENT;

    private HttpServer server;
    private final AtomicReference<HttpExchange> lastExchange = new AtomicReference<>();
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private volatile HttpHandler handler;

    private ChargeRequest charge() {
        return new ChargeRequest(PAYMENT, "00000000-0000-4000-8000-000000000301", "00000000-0000-4000-8000-000000000401",
                150000, "IDR", "tok_sim_card_default", "default", "corr-1");
    }

    private SimulatorBillingProvider provider(Duration callTimeout) {
        return new SimulatorBillingProvider(URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "sim-token",
                Duration.ofSeconds(1), callTimeout);
    }

    private static final String OK_BODY = "{\"reference\":\"sim_ch_0123456789abcdef0123\",\"providerKey\":\"" + KEY
            + "\",\"status\":\"SUCCEEDED\",\"revision\":2,\"amountMinor\":\"150000\",\"currency\":\"IDR\",\"declineCode\":null}";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            lastExchange.set(exchange);
            handler.handle(exchange);
        });
        server.start();
        handler = respond(201, OK_BODY);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static HttpHandler respond(int status, String body) {
        return x -> {
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            x.getResponseHeaders().add("Content-Type", "application/json");
            x.sendResponseHeaders(status, b.length == 0 ? -1 : b.length);
            if (b.length > 0) {
                x.getResponseBody().write(b);
            }
            x.close();
        };
    }

    @Test
    void chargeSendsTheDocumentedRequest() throws Exception {
        var result = provider(Duration.ofSeconds(5)).charge(charge());

        assertThat(result.kind()).isEqualTo(Kind.OBSERVED);
        assertThat(result.observation().outcome()).isEqualTo(ProviderOutcome.SUCCEEDED);
        var x = lastExchange.get();
        assertThat(x.getRequestMethod()).isEqualTo("POST");
        assertThat(x.getRequestURI().getPath()).isEqualTo("/v1/charges");
        assertThat(x.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer sim-token");
        assertThat(x.getRequestHeaders().getFirst("Idempotency-Key")).isEqualTo(KEY);
        assertThat(x.getRequestHeaders().getFirst("X-Correlation-Id")).isEqualTo("corr-1");
        assertThat(x.getRequestHeaders().getFirst("Content-Type")).isEqualTo("application/json");
        JsonNode body = JSON.readTree(lastBody.get());
        assertThat(body.get("amountMinor").isTextual()).as("amounts are strings").isTrue();
        assertThat(body.get("amountMinor").asText()).isEqualTo("150000");
        assertThat(body.get("paymentId").asText()).isEqualTo(PAYMENT);
        assertThat(body.get("methodToken").asText()).isEqualTo("tok_sim_card_default");
        assertThat(body.get("scenarioRunId").asText()).isEqualTo("default");
        assertThat(body.fieldNames()).toIterable().containsExactlyInAnyOrder("paymentId", "merchantId", "invoiceId",
                "amountMinor", "currency", "methodToken", "scenarioRunId");
    }

    @Test
    void aMissingScenarioRunIsSentAsDefault() throws Exception {
        provider(Duration.ofSeconds(5)).charge(new ChargeRequest(PAYMENT, "m", "i", 1, "IDR", "t", null, null));
        assertThat(JSON.readTree(lastBody.get()).get("scenarioRunId").asText()).isEqualTo("default");
        assertThat(lastExchange.get().getRequestHeaders().getFirst("X-Correlation-Id")).isNull();
    }

    @Test
    void lookupUsesTheOriginalKeyOnly() {
        handler = respond(200, OK_BODY);
        var result = provider(Duration.ofSeconds(5)).lookup(new LookupRequest(KEY, 150000, "IDR", 1L, "corr-2"));
        assertThat(result.isObserved()).isTrue();
        var x = lastExchange.get();
        assertThat(x.getRequestMethod()).isEqualTo("GET");
        assertThat(x.getRequestURI().getPath()).isEqualTo("/v1/charges/by-key/" + KEY);
        assertThat(x.getRequestHeaders().getFirst("Idempotency-Key")).isNull();
    }

    @Test
    void lookup404IsNotADecline() {
        handler = respond(404, "{\"error\":{\"code\":\"charge_not_found\",\"message\":\"none\"}}");
        var result = provider(Duration.ofSeconds(5)).lookup(new LookupRequest(KEY, 150000, "IDR", null, null));
        assertThat(result.kind()).isEqualTo(Kind.NOT_FOUND);
        assertThat(result.observation()).isNull();
    }

    @Test
    void aSlowServerIsTransportUncertaintyAfterTheCallTimeout() {
        handler = x -> {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            x.close();
        };
        long start = System.nanoTime();
        var result = provider(Duration.ofMillis(300)).charge(charge());
        assertThat(result.errorClass()).isEqualTo(ErrorClass.TRANSPORT_UNCERTAIN);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
    }

    @Test
    void aConnectionClosedWithoutAResponseIsTransportUncertainty() throws Exception {
        try (var raw = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            var closer = new Thread(() -> {
                try (var socket = raw.accept()) {
                    socket.getInputStream().read(new byte[1024]);          // take the request, answer nothing, hang up
                } catch (IOException ignored) {
                    // the client may already be gone
                }
            });
            closer.start();
            var p = new SimulatorBillingProvider(URI.create("http://127.0.0.1:" + raw.getLocalPort()), "t",
                    Duration.ofSeconds(1), Duration.ofSeconds(5));
            var result = p.charge(charge());
            assertThat(result.errorClass()).isEqualTo(ErrorClass.TRANSPORT_UNCERTAIN);
            assertThat(result.observation()).isNull();
            closer.join(2000);
        }
    }

    @Test
    void connectionRefusedIsRetryableBecauseNothingWasSent() throws Exception {
        int freePort;
        try (var s = new ServerSocket(0)) {
            freePort = s.getLocalPort();
        }
        var p = new SimulatorBillingProvider(URI.create("http://127.0.0.1:" + freePort), "t", Duration.ofSeconds(1), Duration.ofSeconds(2));
        assertThat(p.charge(charge()).errorClass()).isEqualTo(ErrorClass.RETRYABLE_UNAVAILABLE);
        assertThat(p.lookup(new LookupRequest(KEY, 1, "IDR", null, null)).errorClass()).isEqualTo(ErrorClass.RETRYABLE_UNAVAILABLE);
    }

    @Test
    void retryAfterIsHonoredOn503() {
        handler = x -> {
            x.getResponseHeaders().add("Retry-After", "12");
            x.sendResponseHeaders(503, -1);
            x.close();
        };
        var result = provider(Duration.ofSeconds(5)).charge(charge());
        assertThat(result.errorClass()).isEqualTo(ErrorClass.RETRYABLE_UNAVAILABLE);
        assertThat(result.retryAfter()).isEqualTo(Duration.ofSeconds(12));
    }

    @Test
    void redirectsAreNotFollowed() {
        handler = x -> {
            x.getResponseHeaders().add("Location", "http://127.0.0.1:1/elsewhere");
            x.sendResponseHeaders(307, -1);
            x.close();
        };
        assertThat(provider(Duration.ofSeconds(5)).charge(charge()).errorClass()).isEqualTo(ErrorClass.MALFORMED);
    }

    @Test
    void anOversizedBodyIsMalformed() {
        handler = respond(200, "x".repeat(70_000));
        assertThat(provider(Duration.ofSeconds(5)).charge(charge()).errorClass()).isEqualTo(ErrorClass.MALFORMED);
    }

    @Test
    void toStringNeverShowsTheToken() {
        assertThat(provider(Duration.ofSeconds(5)).toString()).doesNotContain("sim-token");
    }
}
