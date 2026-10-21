package com.paybridge.application.port;

import com.paybridge.application.payment.PaymentAdmission;
import java.util.Optional;

/** Quota and capacity decision. Permissive until the Redis quota (T15) and the capacity monitor (T18) arrive. */
public interface AdmissionGate {

    Optional<Closed> check(String merchantId);

    record Closed(PaymentAdmission.Code code, Integer retryAfterSeconds) {}
}
