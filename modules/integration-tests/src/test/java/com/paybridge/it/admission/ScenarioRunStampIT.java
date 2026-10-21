package com.paybridge.it.admission;

import static org.assertj.core.api.Assertions.assertThat;

import com.paybridge.application.payment.PaymentAdmission.Accepted;
import org.junit.jupiter.api.Test;

class ScenarioRunStampIT extends AdmissionIT {

    @Test
    void theConfiguredRunIsStampedAndALaterChangeDoesNotAlterAnAdmittedPayment() {
        scenarioRun.set("run-1");
        String key = key();
        var first = (Accepted) request(key);
        assertThat(rootJdbc.queryForObject("SELECT scenario_run_id FROM payment WHERE id = ?", String.class, first.paymentId()))
                .isEqualTo("run-1");

        scenarioRun.set("run-2");
        var replay = (Accepted) request(key);

        assertThat(replay.paymentId()).isEqualTo(first.paymentId());
        assertThat(rootJdbc.queryForObject("SELECT scenario_run_id FROM payment WHERE id = ?", String.class, first.paymentId()))
                .isEqualTo("run-1");
    }

    @Test
    void thePropertyDefaultIsDefault() {
        var accepted = (Accepted) request(key());
        assertThat(rootJdbc.queryForObject("SELECT scenario_run_id FROM payment WHERE id = ?", String.class, accepted.paymentId()))
                .isEqualTo("default");
    }
}
