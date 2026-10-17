package com.paybridge.simulator.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** paybridge.simulator.* (DMR section 10). Secrets come from the environment only. */
@ConfigurationProperties(prefix = "paybridge.simulator")
public class SimulatorProperties {
    private final Datasource datasource = new Datasource();
    private final Webhook webhook = new Webhook();
    private String bearerToken = "";
    private long completionIntervalMs = 1000;
    private long deliveryIntervalMs = 500;
    private int runTtlHours = 24;
    private int timeoutSleepSeconds = 15;

    public static class Datasource {
        private String url;
        private String username = "pb_sim";
        private String password;
        private final Pool pool = new Pool();

        public static class Pool {
            private int maxSize = 4;

            public int getMaxSize() { return maxSize; }
            public void setMaxSize(int maxSize) { this.maxSize = maxSize; }
        }

        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public Pool getPool() { return pool; }
    }

    public static class Webhook {
        private String targetUrl = "http://core-api:8080/internal/webhooks/simulator";
        private String secret = "";
        private long defaultDelayMs = 500;
        private List<Integer> retryScheduleSeconds = List.of(1, 2, 5, 15, 60, 300, 300, 300);
        private int maxAttempts = 8;

        public String getTargetUrl() { return targetUrl; }
        public void setTargetUrl(String targetUrl) { this.targetUrl = targetUrl; }
        public String getSecret() { return secret; }
        public void setSecret(String secret) { this.secret = secret; }
        public long getDefaultDelayMs() { return defaultDelayMs; }
        public void setDefaultDelayMs(long defaultDelayMs) { this.defaultDelayMs = defaultDelayMs; }
        public List<Integer> getRetryScheduleSeconds() { return retryScheduleSeconds; }
        public void setRetryScheduleSeconds(List<Integer> retryScheduleSeconds) { this.retryScheduleSeconds = retryScheduleSeconds; }
        public int getMaxAttempts() { return maxAttempts; }
        public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
    }

    public Datasource getDatasource() { return datasource; }
    public Webhook getWebhook() { return webhook; }
    public String getBearerToken() { return bearerToken; }
    public void setBearerToken(String bearerToken) { this.bearerToken = bearerToken; }
    public long getCompletionIntervalMs() { return completionIntervalMs; }
    public void setCompletionIntervalMs(long v) { this.completionIntervalMs = v; }
    public long getDeliveryIntervalMs() { return deliveryIntervalMs; }
    public void setDeliveryIntervalMs(long v) { this.deliveryIntervalMs = v; }
    public int getRunTtlHours() { return runTtlHours; }
    public void setRunTtlHours(int v) { this.runTtlHours = v; }
    public int getTimeoutSleepSeconds() { return timeoutSleepSeconds; }
    public void setTimeoutSleepSeconds(int v) { this.timeoutSleepSeconds = v; }
}
