package com.paybridge.it.admission;

import static org.assertj.core.api.Assertions.assertThat;

import com.paybridge.application.payment.PaymentAdmission.Code;
import com.paybridge.application.payment.PaymentAdmission.Rejected;
import com.paybridge.application.port.AdmissionGate;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AdmissionRejectionIT extends AdmissionIT {

    @Test
    void theStaticSwitchRejectsAndCreatesNothing() {
        var result = useCase(settings(false, 3, 3)).admit(actor(), invoice, TOKEN, key());
        assertThat(result).isEqualTo(Rejected.of(Code.TEMPORARILY_UNAVAILABLE, 5));
        assertThat(rowsWritten()).isZero();
    }

    @Test
    void theMaintenanceFlagRejectsAndCreatesNothing() {
        rootJdbc.update("UPDATE system_flag SET flag_value = 'ON' WHERE flag_key = 'admission.maintenance'");
        assertThat(request(key())).isEqualTo(Rejected.of(Code.TEMPORARILY_UNAVAILABLE, 5));
        assertThat(rowsWritten()).isZero();
    }

    @Test
    void theCapacityFlagRejectsAndCreatesNothing() {
        rootJdbc.update("UPDATE system_flag SET flag_value = 'CLOSED' WHERE flag_key = 'admission.capacity'");
        assertThat(request(key())).isEqualTo(Rejected.of(Code.TEMPORARILY_UNAVAILABLE, 5));
        assertThat(rowsWritten()).isZero();
    }

    @Test
    void theQuotaGateRejectsWithItsRetryAfterAndCreatesNothing() {
        gateClosed = Optional.of(new AdmissionGate.Closed(Code.RATE_LIMITED, 7));
        assertThat(request(key())).isEqualTo(Rejected.of(Code.RATE_LIMITED, 7));
        assertThat(rowsWritten()).isZero();
    }

    @Test
    void reopeningAdmissionLetsTheSameKeyThrough() {
        String key = key();
        rootJdbc.update("UPDATE system_flag SET flag_value = 'ON' WHERE flag_key = 'admission.maintenance'");
        request(key);
        rootJdbc.update("UPDATE system_flag SET flag_value = 'OFF' WHERE flag_key = 'admission.maintenance'");
        assertThat(request(key)).isInstanceOf(com.paybridge.application.payment.PaymentAdmission.Accepted.class);
    }
}
