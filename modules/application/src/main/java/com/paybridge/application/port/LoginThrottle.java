package com.paybridge.application.port;

/**
 * Failed login limiter keyed by username and client address. Implementations fail closed: when the backing store is
 * unavailable, {@link #check} throws {@link Unavailable} and the login is refused.
 */
public interface LoginThrottle {

    /** Throws {@link Throttled} while the key is over the limit. */
    void check(String username, String clientAddress);

    /** Counts one failure. Returns true exactly once per window, when the limit is first reached. */
    boolean recordFailure(String username, String clientAddress);

    void reset(String username, String clientAddress);

    final class Throttled extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final long retryAfterSeconds;

        public Throttled(long retryAfterSeconds) {
            super("too many failed attempts");
            this.retryAfterSeconds = retryAfterSeconds;
        }

        public long retryAfterSeconds() {
            return retryAfterSeconds;
        }
    }

    final class Unavailable extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public Unavailable(Throwable cause) {
            super("login throttle store unavailable", cause);
        }
    }
}
