package com.paybridge.application.port;

/** The DMR recovery check queries A to C: admission invariants that must hold after any restore or crash. */
public interface ConsistencyCheck {

    Report run();

    record Report(long pointersToMissingFailedOrForeign, long activePaymentsNotPointedTo, long reservationsWithoutPayment) {
        public boolean clean() {
            return pointersToMissingFailedOrForeign == 0 && activePaymentsNotPointedTo == 0 && reservationsWithoutPayment == 0;
        }
    }
}
