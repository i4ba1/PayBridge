package com.paybridge.core.admission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.paybridge.application.port.ConsistencyCheck;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;

class InvoicePointersHealthIndicatorTest {
    private static final String SECRET_DETAIL = "Table 'paybridge.invoice' doesn't exist at 10.0.0.7";

    @Test
    void aCheckThatThrowsIsSwallowedAndReportsDownWithoutTheExceptionMessage() {
        ConsistencyCheck throwing = () -> {
            throw new IllegalStateException(SECRET_DETAIL);
        };
        var indicator = new InvoicePointersHealthIndicator(throwing);

        assertThatCode(indicator::runCheck).doesNotThrowAnyException();
        var health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).isEqualTo(Map.of("check", "failed"));
        assertThat(health.getDetails().toString()).doesNotContain(SECRET_DETAIL).doesNotContain("IllegalStateException");
    }

    @Test
    void beforeTheCheckRunsTheStateIsUnknownAndADirtyReportIsDownWithCountsOnly() {
        var indicator = new InvoicePointersHealthIndicator(() -> new ConsistencyCheck.Report(1, 0, 2));
        assertThat(indicator.health().getStatus()).isEqualTo(Status.UNKNOWN);

        indicator.runCheck();

        var health = indicator.health();
        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).isEqualTo(Map.of("pointersToMissingFailedOrForeign", 1L,
                "activePaymentsNotPointedTo", 0L, "reservationsWithoutPayment", 2L));
    }
}
