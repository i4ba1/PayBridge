package com.paybridge.core.admission;

import com.paybridge.application.port.ConsistencyCheck;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Runs the admission recovery checks once at startup. A violation makes readiness DOWN so a damaged database never takes
 * traffic. The log and the health details carry counts only, never ids.
 */
@Component("invoicePointersHealthIndicator")
public class InvoicePointersHealthIndicator implements HealthIndicator {
    private static final Logger log = LoggerFactory.getLogger(InvoicePointersHealthIndicator.class);

    private final ConsistencyCheck check;
    private volatile ConsistencyCheck.Report report;
    private volatile boolean failedToRun;

    public InvoicePointersHealthIndicator(ConsistencyCheck check) {
        this.check = check;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void runCheck() {
        try {
            report = check.run();
            failedToRun = false;
        } catch (RuntimeException e) {
            // e.g. an empty schema before the migrate role has run: never stop the application, but never report ready
            failedToRun = true;
            log.error("admission consistency check could not run: {}", e.getClass().getSimpleName());
            return;
        }
        if (!report.clean()) {
            log.error("admission consistency check failed: pointersToMissingFailedOrForeign={} activePaymentsNotPointedTo={} "
                    + "reservationsWithoutPayment={}", report.pointersToMissingFailedOrForeign(),
                    report.activePaymentsNotPointedTo(), report.reservationsWithoutPayment());
        }
    }

    @Override
    public Health health() {
        if (failedToRun) {
            return Health.down().withDetail("check", "failed").build();
        }
        ConsistencyCheck.Report r = report;
        if (r == null) {
            return Health.unknown().withDetail("check", "pending").build();
        }
        var builder = r.clean() ? Health.up() : Health.down();
        return builder.withDetail("pointersToMissingFailedOrForeign", r.pointersToMissingFailedOrForeign())
                .withDetail("activePaymentsNotPointedTo", r.activePaymentsNotPointedTo())
                .withDetail("reservationsWithoutPayment", r.reservationsWithoutPayment()).build();
    }
}
