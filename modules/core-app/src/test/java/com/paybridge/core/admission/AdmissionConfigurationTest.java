package com.paybridge.core.admission;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class AdmissionConfigurationTest {

    @Test
    void defaultsAreValid() {
        assertThatCode(() -> AdmissionConfiguration.validate(new AdmissionProperties())).doesNotThrowAnyException();
    }

    @Test
    void aLockWaitTimeoutBelowOneFailsStartupWithTheKeyName() {
        var p = new AdmissionProperties();
        p.setLockWaitTimeoutSeconds(0);
        assertThatThrownBy(() -> AdmissionConfiguration.validate(p)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("paybridge.admission.lock-wait-timeout-seconds");
    }

    @Test
    void aMethodTokenMaxLengthBelowOneFailsStartupWithTheKeyName() {
        var p = new AdmissionProperties();
        p.setMethodTokenMaxLength(0);
        assertThatThrownBy(() -> AdmissionConfiguration.validate(p)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("paybridge.admission.method-token-max-length");
    }
}
