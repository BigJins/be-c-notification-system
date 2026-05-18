package com.livenotification.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Notification system configuration properties.
 * Full implementation in Task 8 (NotificationProperties + WorkerConfig).
 */
@ConfigurationProperties(prefix = "notification")
public class NotificationProperties {

    private Retry retry = new Retry();
    private Worker worker = new Worker();
    private Admin admin = new Admin();
    private Cleanup cleanup = new Cleanup();

    public Retry getRetry() { return retry; }
    public void setRetry(Retry retry) { this.retry = retry; }

    public Worker getWorker() { return worker; }
    public void setWorker(Worker worker) { this.worker = worker; }

    public Admin getAdmin() { return admin; }
    public void setAdmin(Admin admin) { this.admin = admin; }

    public Cleanup getCleanup() { return cleanup; }
    public void setCleanup(Cleanup cleanup) { this.cleanup = cleanup; }

    public static class Retry {
        private java.time.Duration baseDelay = java.time.Duration.ofSeconds(30);
        private int maxAttempts = 5;
        private double jitterFraction = 1.0;

        public java.time.Duration getBaseDelay() { return baseDelay; }
        public void setBaseDelay(java.time.Duration baseDelay) { this.baseDelay = baseDelay; }

        public int getMaxAttempts() { return maxAttempts; }
        public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }

        public double getJitterFraction() { return jitterFraction; }
        public void setJitterFraction(double jitterFraction) { this.jitterFraction = jitterFraction; }
    }

    public static class Worker {
        private java.time.Duration pollInterval = java.time.Duration.ofSeconds(1);
        private int batchSize = 50;
        private int semaphorePermits = 16;
        private java.time.Duration claimLease = java.time.Duration.ofSeconds(30);
        private java.time.Duration reaperInterval = java.time.Duration.ofSeconds(30);
        private java.time.Duration dispatchTimeout = java.time.Duration.ofSeconds(5);

        public java.time.Duration getPollInterval() { return pollInterval; }
        public void setPollInterval(java.time.Duration pollInterval) { this.pollInterval = pollInterval; }

        public int getBatchSize() { return batchSize; }
        public void setBatchSize(int batchSize) { this.batchSize = batchSize; }

        public int getSemaphorePermits() { return semaphorePermits; }
        public void setSemaphorePermits(int semaphorePermits) { this.semaphorePermits = semaphorePermits; }

        public java.time.Duration getClaimLease() { return claimLease; }
        public void setClaimLease(java.time.Duration claimLease) { this.claimLease = claimLease; }

        public java.time.Duration getReaperInterval() { return reaperInterval; }
        public void setReaperInterval(java.time.Duration reaperInterval) { this.reaperInterval = reaperInterval; }

        public java.time.Duration getDispatchTimeout() { return dispatchTimeout; }
        public void setDispatchTimeout(java.time.Duration dispatchTimeout) { this.dispatchTimeout = dispatchTimeout; }
    }

    public static class Admin {
        private String token = "dev-token-do-not-use-in-prod";

        public String getToken() { return token; }
        public void setToken(String token) { this.token = token; }
    }

    public static class Cleanup {
        private java.time.Duration deliveryAttemptRetention = java.time.Duration.ofDays(30);
        private java.time.Duration idempotencyRetention = java.time.Duration.ofHours(24);

        public java.time.Duration getDeliveryAttemptRetention() { return deliveryAttemptRetention; }
        public void setDeliveryAttemptRetention(java.time.Duration deliveryAttemptRetention) { this.deliveryAttemptRetention = deliveryAttemptRetention; }

        public java.time.Duration getIdempotencyRetention() { return idempotencyRetention; }
        public void setIdempotencyRetention(java.time.Duration idempotencyRetention) { this.idempotencyRetention = idempotencyRetention; }
    }
}
