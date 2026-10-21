package com.paybridge.application.port;

/** The provider scenario run stamped on a payment when it is admitted. */
public interface ScenarioRunResolver {

    String runFor(String merchantId);
}
