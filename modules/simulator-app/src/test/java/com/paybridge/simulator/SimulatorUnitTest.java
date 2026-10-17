package com.paybridge.simulator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.paybridge.simulator.config.SimulatorProperties;
import com.paybridge.simulator.service.ApiException;
import com.paybridge.simulator.service.ChargeBody;
import com.paybridge.simulator.service.WebhookEvents;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.SystemEnvironmentPropertySource;

class SimulatorUnitTest {
    private static final String PAY = "00000000-0000-4000-8000-000000000201";
    private static final String MERCHANT = "00000000-0000-4000-8000-000000000301";
    private static final String INVOICE = "00000000-0000-4000-8000-000000000401";

    private static ChargeBody body(String amount, String currency, String token, String run) {
        return new ChargeBody(PAY, MERCHANT, INVOICE, amount, currency, token, run);
    }

    @Test
    void theWebhookBodyAndSignatureReproduceTheDocumentedWorkedExample() {
        String raw = WebhookEvents.body("7d5c1e0a-3b7e-4f0e-9a55-0d6f3b9c2a11", "pbk-" + PAY, "simch_9f2c41d7a0b3", 2, true,
                Instant.parse("2026-10-16T02:00:07.412Z"), "150000", "IDR");
        assertThat(raw.getBytes(StandardCharsets.UTF_8)).hasSize(286);
        assertThat(raw).startsWith("{\"eventId\":\"7d5c1e0a").doesNotContain(" ").doesNotContain("\n");
        assertThat(WebhookEvents.signature("whsec_demo_0123456789abcdef", 1760580000L, raw.getBytes(StandardCharsets.UTF_8)))
                .isEqualTo("v1=dc8fa64a93cb7044c699414b15c7a08d7a26bd618d3cdf6dddef78e675f8210a");
    }

    @Test
    void aDeclinedEventNamesTheDeclinedOutcome() {
        String raw = WebhookEvents.body("e", "k", "r", 1, false, Instant.parse("2026-10-16T02:00:07.412Z"), "1", "USD");
        assertThat(raw).contains("\"eventType\":\"charge.declined\"").contains("\"outcome\":\"DECLINED\"");
    }

    @Test
    void timestampsHaveMillisecondsInUtc() {
        assertThat(WebhookEvents.iso(Instant.parse("2026-10-16T02:00:10.300999Z"))).isEqualTo("2026-10-16T02:00:10.300Z");
        assertThat(WebhookEvents.iso(Instant.parse("2026-10-16T02:00:10Z"))).isEqualTo("2026-10-16T02:00:10.000Z");
    }

    @Test
    void theRequestHashCoversEveryImmutableFieldAndIsStable() {
        String base = body("1000", "IDR", "tok_sim_card_default", "default").requestHash();
        assertThat(base).hasSize(64).isEqualTo(body("1000", "IDR", "tok_sim_card_default", "default").requestHash());
        assertThat(body("1001", "IDR", "tok_sim_card_default", "default").requestHash()).isNotEqualTo(base);
        assertThat(body("1000", "USD", "tok_sim_card_default", "default").requestHash()).isNotEqualTo(base);
        assertThat(body("1000", "IDR", "tok_sim_card_alt", "default").requestHash()).isNotEqualTo(base);
        assertThat(body("1000", "IDR", "tok_sim_card_default", "other-run").requestHash()).isNotEqualTo(base);
        assertThat(new ChargeBody(PAY, MERCHANT, "00000000-0000-4000-8000-000000000402", "1000", "IDR", "tok_sim_card_default", "default").requestHash())
                .isNotEqualTo(base);
    }

    @Test
    void validationAcceptsTheDocumentedExampleAndDefaultsTheRun() {
        var valid = body("150000", "IDR", "tok_sim_card_default", null).withValidatedDefaults("pbk-" + PAY);
        assertThat(valid.scenarioRunId()).isEqualTo("default");
        assertThat(body("1", "USD", "tok_sim_transfer_default", "run-1.a_b").withValidatedDefaults("pbk-" + PAY)).isNotNull();
    }

    @Test
    void validationRejectsEverythingTheContractForbids() {
        String key = "pbk-" + PAY;
        for (ChargeBody bad : new ChargeBody[] {
                body("0", "IDR", "tok_sim_card_default", "default"), body("01", "IDR", "tok_sim_card_default", "default"),
                body("1.5", "IDR", "tok_sim_card_default", "default"), body(null, "IDR", "tok_sim_card_default", "default"),
                body("1000", "eur", "tok_sim_card_default", "default"), body("1000", "IDR", "4111111111111111", "default"),
                body("1000", "IDR", "tok_sim_card_default", "bad run"), body("1000", "IDR", "tok_sim_card_default", "x".repeat(65)),
                new ChargeBody("not-a-uuid", MERCHANT, INVOICE, "1", "IDR", "tok_sim_card_default", "default")}) {
            assertThatThrownBy(() -> bad.withValidatedDefaults(key)).as(bad.toString()).isInstanceOf(ApiException.class)
                    .extracting(e -> ((ApiException) e).status()).isEqualTo(400);
        }
        var ok = body("1000", "IDR", "tok_sim_card_default", "default");
        for (String badKey : new String[] {null, "", "pbk-" + PAY + "x", PAY, "pbk-00000000-0000-4000-8000-000000000999", "pbk-é"}) {
            assertThatThrownBy(() -> ok.withValidatedDefaults(badKey)).as(String.valueOf(badKey)).isInstanceOf(ApiException.class);
        }
    }

    @Test
    void settingsBindFromTheEnvironmentVariableNamesTheChartRenders() {
        var env = new SystemEnvironmentPropertySource("systemEnvironment", Map.of(
                "PAYBRIDGE_SIMULATOR_DATASOURCE_URL", "jdbc:mysql://mysql-writer:3306/paybridge_sim",
                "PAYBRIDGE_SIMULATOR_DATASOURCE_USERNAME", "pb_sim",
                "PAYBRIDGE_SIMULATOR_DATASOURCE_PASSWORD", "x",
                "PAYBRIDGE_SIMULATOR_DATASOURCE_POOL_MAX_SIZE", "7",
                "PAYBRIDGE_SIMULATOR_BEARER_TOKEN", "t",
                "PAYBRIDGE_SIMULATOR_WEBHOOK_TARGET_URL", "http://core:8080/internal/webhooks/simulator",
                "PAYBRIDGE_SIMULATOR_WEBHOOK_SECRET", "s",
                "PAYBRIDGE_SIMULATOR_WEBHOOK_RETRY_SCHEDULE_SECONDS", "1,2,3",
                "PAYBRIDGE_SIMULATOR_WEBHOOK_MAX_ATTEMPTS", "5",
                "PAYBRIDGE_SIMULATOR_COMPLETION_INTERVAL_MS", "250"));
        var sources = new MutablePropertySources();
        sources.addFirst(new MapPropertySource("empty", Map.of()));
        sources.addLast(env);
        var props = new Binder(ConfigurationPropertySources.from(sources)).bind("paybridge.simulator", SimulatorProperties.class).get();
        assertThat(props.getDatasource().getUrl()).endsWith("/paybridge_sim");
        assertThat(props.getDatasource().getPool().getMaxSize()).isEqualTo(7);
        assertThat(props.getBearerToken()).isEqualTo("t");
        assertThat(props.getWebhook().getRetryScheduleSeconds()).containsExactly(1, 2, 3);
        assertThat(props.getWebhook().getMaxAttempts()).isEqualTo(5);
        assertThat(props.getWebhook().getSecret()).isEqualTo("s");
        assertThat(props.getCompletionIntervalMs()).isEqualTo(250);
    }

    @Test
    void defaultsMatchTheConfigurationReference() {
        var props = new SimulatorProperties();
        assertThat(props.getWebhook().getRetryScheduleSeconds()).containsExactly(1, 2, 5, 15, 60, 300, 300, 300);
        assertThat(props.getWebhook().getMaxAttempts()).isEqualTo(8);
        assertThat(props.getWebhook().getDefaultDelayMs()).isEqualTo(500);
        assertThat(props.getDeliveryIntervalMs()).isEqualTo(500);
        assertThat(props.getCompletionIntervalMs()).isEqualTo(1000);
        assertThat(props.getRunTtlHours()).isEqualTo(24);
        assertThat(props.getTimeoutSleepSeconds()).isEqualTo(15);
        assertThat(props.getDatasource().getUsername()).isEqualTo("pb_sim");
        assertThat(props.getDatasource().getPool().getMaxSize()).isEqualTo(4);
    }
}
