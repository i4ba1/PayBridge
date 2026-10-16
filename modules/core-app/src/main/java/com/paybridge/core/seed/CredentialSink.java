package com.paybridge.core.seed;

import java.util.Map;

/**
 * Where freshly generated demo passwords go (the Kubernetes Secret paybridge-demo-accounts, or a local file in tests).
 * Implementations must never log a value. The map is username to plaintext password, only for accounts created now.
 */
public interface CredentialSink {
    /** Merges the entries into the stored set; entries not named here are left untouched. */
    void store(Map<String, String> passwordsByUsername);
}
