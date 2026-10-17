package com.paybridge.it.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paybridge.adapter.provider.SimulatorBillingProvider;
import com.paybridge.application.port.BillingProvider;
import com.paybridge.application.port.BillingProvider.ChargeRequest;
import com.paybridge.application.port.BillingProvider.LookupRequest;
import com.paybridge.application.port.BillingProvider.ProviderResult;
import com.paybridge.it.support.MysqlSupport;
import com.paybridge.it.support.SimulatorSupport;
import com.paybridge.it.support.WebhookReceiver;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.springframework.jdbc.core.JdbcTemplate;

/** The real simulator in this JVM, its own MySQL schema, a webhook receiver, and the real adapter in front of it. */
@Tag("integration")
public abstract class SimulatorIT {
    protected static final ObjectMapper JSON = new ObjectMapper();
    protected static final String MERCHANT = "00000000-0000-4000-8000-000000000301";
    protected static final String INVOICE = "00000000-0000-4000-8000-000000000401";
    protected static WebhookReceiver receiver;
    protected static SimulatorSupport.Running simulator;
    protected static HikariDataSource simDb;
    protected static JdbcTemplate simJdbc;
    protected static final HttpClient HTTP = HttpClient.newHttpClient();

    @BeforeAll
    static void startSimulator() {
        SimulatorSupport.resetDatabase();
        receiver = new WebhookReceiver();
        simulator = SimulatorSupport.start(receiver.url());
        simDb = (HikariDataSource) rootDataSourceFor(SimulatorSupport.SCHEMA);
        simJdbc = new JdbcTemplate(simDb);
    }

    @AfterAll
    static void stopSimulator() {
        simulator.close();
        receiver.close();
        simDb.close();
    }

    private static javax.sql.DataSource rootDataSourceFor(String schema) {
        var c = new com.zaxxer.hikari.HikariConfig();
        c.setJdbcUrl(MysqlSupport.jdbcUrl().replace("/" + MysqlSupport.SCHEMA + "?", "/" + schema + "?"));
        c.setUsername("root");
        c.setPassword(((HikariDataSource) MysqlSupport.rootDataSource()).getPassword());
        c.setMaximumPoolSize(3);
        return new HikariDataSource(c);
    }

    // ---- adapter -----------------------------------------------------------------------------------------------

    protected static SimulatorBillingProvider provider() {
        return provider(Duration.ofSeconds(10));
    }

    protected static SimulatorBillingProvider provider(Duration callTimeout) {
        return new SimulatorBillingProvider(simulator.baseUri(), SimulatorSupport.TOKEN, Duration.ofSeconds(2), callTimeout);
    }

    protected static String newPaymentId() {
        return UUID.randomUUID().toString();
    }

    protected static ChargeRequest charge(String paymentId, long amount, String token, String run) {
        return new ChargeRequest(paymentId, MERCHANT, INVOICE, amount, "IDR", token, run, "it-" + paymentId.substring(0, 8));
    }

    protected static LookupRequest lookup(String paymentId, long amount) {
        return new LookupRequest(BillingProvider.providerKey(paymentId), amount, "IDR", null, null);
    }

    protected static ProviderResult lookupResult(String paymentId, long amount) {
        return provider().lookup(lookup(paymentId, amount));
    }

    // ---- raw HTTP ----------------------------------------------------------------------------------------------

    public record Raw(int status, JsonNode body, java.net.http.HttpHeaders headers) {}

    protected static Raw http(String method, String path, String bearer, String body, String... headers) {
        try {
            var b = HttpRequest.newBuilder(URI.create(simulator.baseUri() + path)).timeout(Duration.ofSeconds(20));
            if (bearer != null) {
                b.header("Authorization", "Bearer " + bearer);
            }
            for (int i = 0; i < headers.length; i += 2) {
                b.header(headers[i], headers[i + 1]);
            }
            if (body != null) {
                b.header("Content-Type", "application/json");
            }
            b.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
            return new Raw(r.statusCode(), r.body().isBlank() ? JSON.createObjectNode() : JSON.readTree(r.body()), r.headers());
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    protected static Raw admin(String method, String path, String body) {
        return http(method, path, SimulatorSupport.TOKEN, body);
    }

    protected static void createRun(String json) {
        var r = admin("POST", "/internal/scenario-runs", json);
        if (r.status() != 201) {
            throw new IllegalStateException("run not created: " + r.status() + " " + r.body());
        }
    }

    protected static int charges(String paymentId) {
        return simJdbc.queryForObject("SELECT COUNT(*) FROM sim_charge WHERE idempotency_key = ?", Integer.class,
                BillingProvider.providerKey(paymentId));
    }

    protected static void await(String what, Duration max, BooleanSupplier condition) {
        long deadline = System.nanoTime() + max.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw new AssertionError("timed out waiting for " + what);
    }
}
