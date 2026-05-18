package com.livenotification.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "notification")
public record NotificationProperties(Retry retry, Worker worker, Admin admin, Cleanup cleanup) {
    public record Retry(Duration baseDelay, int maxAttempts, double jitterFraction) {}
    public record Worker(Duration pollInterval, int batchSize, int semaphorePermits,
                          Duration claimLease, Duration reaperInterval, Duration dispatchTimeout) {}
    public record Admin(String token) {}
    public record Cleanup(Duration deliveryAttemptRetention, Duration idempotencyRetention) {}
}
