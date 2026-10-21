package com.paybridge.core.admission;

import com.paybridge.application.port.AdmissionGate;
import java.util.Optional;

/** Stub until the Redis quota (T15) and the capacity monitor (T18) replace it. Those tasks add a test that fails if it is still wired. */
public class PermissiveAdmissionGate implements AdmissionGate {
    @Override
    public Optional<Closed> check(String merchantId) {
        return Optional.empty();
    }
}
