package com.paybridge.it.admission;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paybridge.application.payment.PaymentAdmission.Accepted;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AsyncAcknowledgementIT extends AdmissionIT {

    @Test
    void theAcknowledgementIsRequestedAndOnlyDurableWorkExists() throws Exception {
        var accepted = (Accepted) request(key());
        assertThat(accepted.status()).isEqualTo("REQUESTED");
        assertThat(accepted.aggregateVersion()).isEqualTo(1);

        var payment = rootJdbc.queryForMap("SELECT status, aggregate_version, version, provider_key, provider_reference, "
                + "provider_revision, amount_minor, currency, method_code, method_token, requested_by, correlation_id "
                + "FROM payment WHERE id = ?", accepted.paymentId());
        assertThat(payment).containsEntry("status", "REQUESTED").containsEntry("aggregate_version", 1L)
                .containsEntry("version", 0L).containsEntry("provider_key", "pbk-" + accepted.paymentId())
                .containsEntry("amount_minor", 150_000L).containsEntry("currency", "IDR")
                .containsEntry("method_code", METHOD).containsEntry("method_token", TOKEN)
                .containsEntry("requested_by", user).containsEntry("correlation_id", "corr-1");
        assertThat(payment.get("provider_reference")).isNull();
        assertThat(payment.get("provider_revision")).isNull();

        assertThat(rootJdbc.queryForObject("SELECT COUNT(*) FROM payment_attempt WHERE payment_id = ?", Long.class,
                accepted.paymentId())).as("no provider attempt exists").isZero();
        assertThat(rootJdbc.queryForObject("SELECT COUNT(*) FROM processing_task WHERE payment_id = ?", Long.class,
                accepted.paymentId())).as("the task is created by the intake consumer (T09)").isZero();

        var event = rootJdbc.queryForMap("SELECT event_id, event_class, event_type, schema_version, aggregate_version, "
                + "correlation_id, CAST(payload AS CHAR) AS payload FROM payment_event WHERE payment_id = ?", accepted.paymentId());
        assertThat(event).containsEntry("event_class", "STATE").containsEntry("event_type", "PaymentRequested")
                .containsEntry("schema_version", 1).containsEntry("aggregate_version", 1L)
                .containsEntry("correlation_id", "corr-1");
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = new ObjectMapper().readValue((String) event.get("payload"), Map.class);
        assertThat(payload).containsOnlyKeys("invoiceId", "invoiceReference", "methodCode", "amountMinor", "currency", "providerKey")
                .containsEntry("amountMinor", "150000").containsEntry("providerKey", "pbk-" + accepted.paymentId())
                .containsEntry("invoiceId", invoice).containsEntry("methodCode", METHOD);
        assertThat(event.get("payload").toString()).doesNotContain(TOKEN);

        var outbox = rootJdbc.queryForList("SELECT destination, exchange, routing_key, status FROM outbox WHERE event_id = ? "
                + "ORDER BY destination", event.get("event_id"));
        assertThat(outbox).extracting(r -> r.get("destination") + ":" + r.get("exchange") + ":" + r.get("routing_key") + ":" + r.get("status"))
                .containsExactly("COMMAND:paybridge.commands:payment.collect.v1:PENDING",
                        "STATUS:paybridge.events:payment.status.v1:PENDING");

        assertThat(rootJdbc.queryForObject("SELECT active_payment_id FROM invoice WHERE id = ?", String.class, invoice))
                .isEqualTo(accepted.paymentId());
        List<Map<String, Object>> audit = rootJdbc.queryForList("SELECT actor_type, actor_user_id, target_type, target_id, result "
                + "FROM audit_action WHERE merchant_id = ? AND action = 'PAYMENT_ADMITTED'", merchant);
        assertThat(audit).hasSize(1);
        assertThat(audit.get(0)).containsEntry("actor_type", "USER").containsEntry("actor_user_id", user)
                .containsEntry("target_type", "payment").containsEntry("target_id", accepted.paymentId())
                .containsEntry("result", "SUCCESS");
    }
}
