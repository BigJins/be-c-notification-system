package com.livenotification.delivery.domain;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Random;
import java.util.random.RandomGenerator;

/**
 * Pure POJO retry policy. No Spring annotations.
 * Decides next backoff delay and DEAD-vs-retry given a DispatchResult.
 *
 * Formula: base * 2^(attempts-1) + jitter[0, base * jitterFraction)
 */
public class RetryPolicy {

    private final Duration baseDelay;
    private final int maxAttempts;
    private final double jitterFraction;
    private final RandomGenerator random;

    public RetryPolicy(Duration baseDelay, int maxAttempts, double jitterFraction) {
        this(baseDelay, maxAttempts, jitterFraction, new Random());
    }

    /** Test-friendly constructor accepting a custom RandomGenerator (seedable). */
    public RetryPolicy(Duration baseDelay, int maxAttempts, double jitterFraction, RandomGenerator random) {
        if (baseDelay == null || baseDelay.isNegative() || baseDelay.isZero())
            throw new IllegalArgumentException("baseDelay must be > 0");
        if (maxAttempts < 1)
            throw new IllegalArgumentException("maxAttempts must be >= 1");
        if (jitterFraction < 0.0)
            throw new IllegalArgumentException("jitterFraction must be >= 0");
        this.baseDelay = baseDelay;
        this.maxAttempts = maxAttempts;
        this.jitterFraction = jitterFraction;
        this.random = random;
    }

    public int maxAttempts() { return maxAttempts; }

    /**
     * Compute next attempt time given current session count just completed.
     * n=1 → base + jitter; n=2 → 2*base + jitter; n=3 → 4*base + jitter ...
     */
    public Instant nextAttemptAt(DeliveryAttemptSessionCount n, Clock clock) {
        if (n.value() < 1)
            throw new IllegalArgumentException("nextAttemptAt requires n >= 1, got " + n.value());
        long backoffMs = baseDelay.toMillis() * (1L << (n.value() - 1));
        double jitterMaxMs = baseDelay.toMillis() * jitterFraction;
        long jitterMs = (long) (random.nextDouble() * jitterMaxMs);
        return clock.instant().plusMillis(backoffMs + jitterMs);
    }

    /**
     * Decide whether to DEAD or schedule retry given the dispatch result and post-attempt count.
     * - PermanentFailure → always DEAD.
     * - TransientFailure with n >= maxAttempts → DEAD.
     * - Otherwise → retry.
     */
    public boolean shouldDead(DeliveryAttemptSessionCount n, DispatchResult result) {
        if (result instanceof DispatchResult.PermanentFailure) return true;
        if (result instanceof DispatchResult.TransientFailure) return n.value() >= maxAttempts;
        return false;   // Success — caller shouldn't ask
    }
}
