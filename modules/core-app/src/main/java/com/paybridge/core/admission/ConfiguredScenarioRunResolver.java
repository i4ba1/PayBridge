package com.paybridge.core.admission;

import com.paybridge.application.port.ScenarioRunResolver;

/** Every payment gets the configured run until merchants can pick one (scenario administration, T16 and T17). */
public class ConfiguredScenarioRunResolver implements ScenarioRunResolver {
    private final String runId;

    public ConfiguredScenarioRunResolver(String runId) {
        this.runId = runId;
    }

    @Override
    public String runFor(String merchantId) {
        return runId;
    }
}
