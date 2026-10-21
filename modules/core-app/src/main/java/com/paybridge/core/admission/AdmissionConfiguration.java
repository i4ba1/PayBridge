package com.paybridge.core.admission;

import com.paybridge.application.payment.AdmitPayment;
import com.paybridge.application.port.AdmissionGate;
import com.paybridge.application.port.AdmissionStore;
import com.paybridge.application.port.ScenarioRunResolver;
import com.paybridge.application.port.SystemFlags;
import java.util.UUID;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AdmissionProperties.class)
public class AdmissionConfiguration {

    @Bean
    AdmissionGate admissionGate() {
        return new PermissiveAdmissionGate();
    }

    @Bean
    ScenarioRunResolver scenarioRunResolver(AdmissionProperties properties) {
        return new ConfiguredScenarioRunResolver(properties.getDefaultScenarioRunId());
    }

    @Bean
    AdmitPayment admitPayment(AdmissionStore store, AdmissionGate gate, SystemFlags flags, ScenarioRunResolver runs,
                              AdmissionProperties p) {
        validate(p);
        return new AdmitPayment(store, gate, flags, runs,
                new AdmitPayment.Settings(p.isEnabled(), p.getMethodTokenMaxLength(), p.getLockWaitTimeoutSeconds(), 3, 50, 5),
                AdmissionConfiguration::sleep, () -> UUID.randomUUID().toString());
    }

    /** Fails startup with a clear message instead of failing the first payment. */
    static void validate(AdmissionProperties p) {
        if (p.getLockWaitTimeoutSeconds() < 1) {
            throw new IllegalStateException("paybridge.admission.lock-wait-timeout-seconds must be at least 1 but is "
                    + p.getLockWaitTimeoutSeconds());
        }
        if (p.getMethodTokenMaxLength() < 1) {
            throw new IllegalStateException("paybridge.admission.method-token-max-length must be at least 1 but is "
                    + p.getMethodTokenMaxLength());
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
