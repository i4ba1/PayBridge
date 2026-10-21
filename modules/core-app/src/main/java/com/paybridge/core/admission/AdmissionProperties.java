package com.paybridge.core.admission;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** paybridge.admission.* (DMR section 10). */
@ConfigurationProperties(prefix = "paybridge.admission")
public class AdmissionProperties {
    private boolean enabled = true;
    private int lockWaitTimeoutSeconds = 3;
    private int methodTokenMaxLength = 128;
    private String defaultScenarioRunId = "default";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getLockWaitTimeoutSeconds() { return lockWaitTimeoutSeconds; }
    public void setLockWaitTimeoutSeconds(int v) { this.lockWaitTimeoutSeconds = v; }
    public int getMethodTokenMaxLength() { return methodTokenMaxLength; }
    public void setMethodTokenMaxLength(int v) { this.methodTokenMaxLength = v; }
    public String getDefaultScenarioRunId() { return defaultScenarioRunId; }
    public void setDefaultScenarioRunId(String v) { this.defaultScenarioRunId = v; }
}
