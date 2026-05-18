package com.livenotification.delivery.domain;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.random.RandomGenerator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RetryPolicyTest {

    private final Clock fixedClock = Clock.fixed(Instant.parse("2026-05-18T12:00:00Z"), ZoneOffset.UTC);
    /** Deterministic random — always returns 0.0 so jitter is removed. */
    private final RandomGenerator zeroRandom = new RandomGenerator() {
        @Override public long nextLong() { return 0L; }
        @Override public double nextDouble() { return 0.0; }
    };

    private RetryPolicy policy(int max) {
        return new RetryPolicy(Duration.ofSeconds(30), max, 1.0, zeroRandom);
    }

    @Test
    void nextAttemptAt_expBackoff_n1_baseDelay() {
        Instant next = policy(5).nextAttemptAt(new DeliveryAttemptSessionCount(1), fixedClock);
        assertThat(Duration.between(fixedClock.instant(), next)).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void nextAttemptAt_expBackoff_n2_doubleDelay() {
        Instant next = policy(5).nextAttemptAt(new DeliveryAttemptSessionCount(2), fixedClock);
        assertThat(Duration.between(fixedClock.instant(), next)).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void nextAttemptAt_expBackoff_n5_16xDelay() {
        Instant next = policy(5).nextAttemptAt(new DeliveryAttemptSessionCount(5), fixedClock);
        assertThat(Duration.between(fixedClock.instant(), next)).isEqualTo(Duration.ofSeconds(480));
    }

    @Test
    void nextAttemptAt_n_lessThan_1_throws() {
        assertThatThrownBy(() ->
            policy(5).nextAttemptAt(new DeliveryAttemptSessionCount(0), fixedClock))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldDead_permanent_true() {
        DispatchResult permanent = new DispatchResult.PermanentFailure("p", new RuntimeException());
        assertThat(policy(5).shouldDead(new DeliveryAttemptSessionCount(1), permanent)).isTrue();
    }

    @Test
    void shouldDead_transient_belowMax_false() {
        DispatchResult trans = new DispatchResult.TransientFailure("t", new RuntimeException());
        assertThat(policy(5).shouldDead(new DeliveryAttemptSessionCount(4), trans)).isFalse();
    }

    @Test
    void shouldDead_transient_atMax_true() {
        DispatchResult trans = new DispatchResult.TransientFailure("t", new RuntimeException());
        assertThat(policy(3).shouldDead(new DeliveryAttemptSessionCount(3), trans)).isTrue();
    }
}
