package com.paybridge.it.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.paybridge.it.support.SimulatorSupport;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Authentication, the operator listing and the shape of responses (API contract 8.1 to 8.3). */
class SimulatorApiIT extends SimulatorIT {
    private static final String CARD = "tok_sim_card_default";
    private static final String MILLIS_ISO = "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z";

    @Test
    void everyProtectedEndpointNeedsTheBearerToken() {
        String[][] endpoints = {{"POST", "/v1/charges"}, {"GET", "/v1/charges/by-key/pbk-x"}, {"POST", "/internal/scenario-runs"},
                {"GET", "/internal/scenario-runs/default"}, {"POST", "/internal/scenario-runs/default/deactivate"}, {"GET", "/internal/operations"}};
        for (String[] e : endpoints) {
            for (String token : new String[] {null, "", "wrong", SimulatorSupport.TOKEN + "x", SimulatorSupport.TOKEN.substring(1)}) {
                var r = http(e[0], e[1], token, e[0].equals("POST") ? "{}" : null);
                assertThat(r.status()).as(e[0] + " " + e[1] + " token=" + token).isEqualTo(401);
                assertThat(r.body().path("error").path("code").asText()).isEqualTo("unauthorized");
            }
        }
    }

    @Test
    void theChargeShapeMatchesTheContract() {
        String pay = newPaymentId();
        var r = http("POST", "/v1/charges", SimulatorSupport.TOKEN, ProviderIdempotencyIT.body(pay, MERCHANT, INVOICE, "150000", "IDR", CARD, "default"),
                "Idempotency-Key", "pbk-" + pay, "X-Correlation-Id", "corr-api-1");
        assertThat(r.status()).isEqualTo(201);
        var b = r.body();
        assertThat(b.fieldNames()).toIterable().containsExactlyInAnyOrder("reference", "providerKey", "status", "revision",
                "amountMinor", "currency", "declineCode", "createdAt", "updatedAt");
        assertThat(b.path("reference").asText()).matches("sim_ch_[0-9a-f]{20}");
        assertThat(b.path("providerKey").asText()).isEqualTo("pbk-" + pay);
        assertThat(b.path("amountMinor").isTextual()).isTrue();
        assertThat(b.path("revision").isIntegralNumber()).isTrue();
        assertThat(b.path("declineCode").isNull()).isTrue();
        assertThat(b.path("createdAt").asText()).matches(MILLIS_ISO);
        assertThat(b.path("updatedAt").asText()).matches(MILLIS_ISO);
        assertThat(r.headers().firstValue("X-Correlation-Id")).contains("corr-api-1");
        assertThat(r.headers().firstValue("Content-Type").orElse("")).startsWith("application/json");
    }

    @Test
    void anErrorHasTheDocumentedEnvelope() {
        var r = http("GET", "/v1/charges/by-key/pbk-not-here", SimulatorSupport.TOKEN, null);
        assertThat(r.status()).isEqualTo(404);
        assertThat(r.body().path("error").path("code").asText()).isEqualTo("charge_not_found");
        assertThat(r.body().path("error").path("message").asText()).isNotBlank();
        assertThat(r.body().fieldNames()).toIterable().containsExactly("error");
    }

    @Test
    void theOperationsListingPagesNewestFirstAndFiltersByTimeAndStatus() throws Exception {
        createRun("{\"runId\":\"run-ops-decline\",\"scenario\":\"DECLINE\"}");
        var paid = new ArrayList<String>();
        for (int i = 0; i < 5; i++) {
            String pay = newPaymentId();
            paid.add("pbk-" + pay);
            provider().charge(charge(pay, 1000 + i, CARD, i == 4 ? "run-ops-decline" : "default"));
            Thread.sleep(15);
        }
        var firstPage = admin("GET", "/internal/operations?limit=2&from=2020-01-01T00:00:00Z", null).body();
        assertThat(firstPage.path("items")).hasSize(2);
        assertThat(firstPage.path("nextCursor").isNull()).isFalse();
        assertThat(firstPage.path("items").get(0).path("providerKey").asText()).as("newest first").isEqualTo(paid.get(4));
        assertThat(firstPage.path("items").get(0).fieldNames()).toIterable().containsExactlyInAnyOrder("reference", "providerKey",
                "paymentId", "scenarioRunId", "status", "revision", "amountMinor", "currency", "createdAt", "updatedAt");

        var seen = new ArrayList<String>();
        String cursor = null;
        do {
            var page = admin("GET", "/internal/operations?limit=2&from=2020-01-01T00:00:00Z" + (cursor == null ? "" : "&cursor=" + cursor), null).body();
            page.path("items").forEach(i -> seen.add(i.path("providerKey").asText()));
            cursor = page.path("nextCursor").isNull() ? null : page.path("nextCursor").asText();
        } while (cursor != null);
        assertThat(seen).doesNotHaveDuplicates().containsAll(paid);

        var declined = admin("GET", "/internal/operations?status=DECLINED&limit=100", null).body();
        List<String> statuses = new ArrayList<>();
        declined.path("items").forEach(i -> statuses.add(i.path("status").asText()));
        assertThat(statuses).isNotEmpty().containsOnly("DECLINED");
        var future = admin("GET", "/internal/operations?from=2999-01-01T00:00:00Z", null).body();
        assertThat(future.path("items")).isEmpty();
    }

    @Test
    void theOperationsListingValidatesItsInput() {
        assertThat(admin("GET", "/internal/operations?limit=101", null).status()).isEqualTo(400);
        assertThat(admin("GET", "/internal/operations?limit=0", null).status()).isEqualTo(400);
        assertThat(admin("GET", "/internal/operations?from=yesterday", null).status()).isEqualTo(400);
        assertThat(admin("GET", "/internal/operations?status=REFUNDED", null).status()).isEqualTo(400);
        assertThat(admin("GET", "/internal/operations?cursor=%25%25", null).status()).isEqualTo(400);
    }

    @Test
    void scenarioRunsReportTheirShape() {
        createRun("{\"runId\":\"run-shape\",\"scenario\":\"DELAYED_TRANSFER\",\"delaySeconds\":7,\"maxUses\":3}");
        var run = admin("GET", "/internal/scenario-runs/run-shape", null).body();
        assertThat(run.path("scenario").asText()).isEqualTo("DELAYED_TRANSFER");
        assertThat(run.path("delaySeconds").asInt()).isEqualTo(7);
        assertThat(run.path("state").asText()).isEqualTo("ACTIVE");
        assertThat(run.path("maxUses").asInt()).isEqualTo(3);
        assertThat(run.path("usedCount").asInt()).isZero();
        assertThat(run.path("createdAt").asText()).matches(MILLIS_ISO);
        assertThat(run.path("expiresAt").asText()).matches(MILLIS_ISO);
    }

    @Test
    void theSimulatorAccountCannotTouchThePayBridgeSchema() throws Exception {
        try (var ds = new com.zaxxer.hikari.HikariDataSource(config("pb_sim"))) {
            var jdbc = new org.springframework.jdbc.core.JdbcTemplate(ds);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.queryForList("SELECT 1 FROM paybridge.app_user"))
                    .hasStackTraceContaining("denied");
        }
    }

    private static com.zaxxer.hikari.HikariConfig config(String user) {
        var c = new com.zaxxer.hikari.HikariConfig();
        c.setJdbcUrl(com.paybridge.it.support.MysqlSupport.jdbcUrl().replace("/paybridge?", "/paybridge_sim?"));
        c.setUsername(user);
        c.setPassword(com.paybridge.it.support.MysqlSupport.PASSWORD);
        c.setMaximumPoolSize(1);
        return c;
    }
}
