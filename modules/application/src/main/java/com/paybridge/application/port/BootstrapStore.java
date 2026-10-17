package com.paybridge.application.port;

import java.util.Optional;

/** The first run singleton row (bootstrap_state). All methods expect an open transaction. */
public interface BootstrapStore {

    /** Locks the singleton row for the rest of the transaction. Empty only when the seed never ran. */
    Optional<Row> lock();

    void markExpired();

    /** Moves PENDING to CONSUMED and clears the hash. Returns false when the row was not PENDING. */
    boolean consume(String administratorId);

    enum State { PENDING, CONSUMED, EXPIRED }

    /** expired is decided by the database clock. */
    record Row(State state, String tokenHash, boolean expired) {
        @Override
        public String toString() {
            return "Row[state=" + state + ", expired=" + expired + "]";
        }
    }
}
