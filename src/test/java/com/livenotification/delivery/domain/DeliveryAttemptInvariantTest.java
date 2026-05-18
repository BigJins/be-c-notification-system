package com.livenotification.delivery.domain;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeliveryAttemptInvariantTest {

    private final Clock fixedClock = Clock.fixed(Instant.parse("2026-05-18T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void stateTransitions_ready_inProgress_done_illegalDirectTransitionThrows() {
        // Invariant 1: readyFor → READY → claim → IN_PROGRESS → markDone → DONE.
        // Illegal transition (direct READY→DONE) throws IllegalStateException.
        DeliveryId deliveryId = new DeliveryId(UUID.randomUUID());
        Instant now = fixedClock.instant();

        DeliveryAttempt attempt = DeliveryAttempt.readyFor(deliveryId, fixedClock);
        assertThat(attempt.getState()).isEqualTo(DeliveryAttemptState.READY);

        attempt.claim("worker-1", now, now.plusSeconds(30));
        assertThat(attempt.getState()).isEqualTo(DeliveryAttemptState.IN_PROGRESS);
        assertThat(attempt.getClaimedBy()).isEqualTo("worker-1");
        assertThat(attempt.getClaimedUntil()).isEqualTo(now.plusSeconds(30));

        attempt.markDone(now);
        assertThat(attempt.getState()).isEqualTo(DeliveryAttemptState.DONE);

        // Illegal transition: DONE → markDone (already done) must throw
        assertThatThrownBy(() -> attempt.markDone(now))
            .isInstanceOf(IllegalStateException.class);

        // Illegal transition: READY → markDone (skipping IN_PROGRESS) must throw
        DeliveryAttempt ready2 = DeliveryAttempt.readyFor(deliveryId, fixedClock);
        assertThatThrownBy(() -> ready2.markDone(now))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void attemptCount_isMonotonicallyIncreasing_scheduleNextRetryIncrements() {
        // Invariant 2: 0 ≤ attempt_count ≤ max_attempts; scheduleNextRetry increments.
        // (Max boundary is RetryPolicy responsibility, not tested here.)
        DeliveryId deliveryId = new DeliveryId(UUID.randomUUID());
        Instant now = fixedClock.instant();

        DeliveryAttempt attempt = DeliveryAttempt.readyFor(deliveryId, fixedClock);
        assertThat(attempt.getAttemptCount().value()).isZero();

        attempt.claim("worker-1", now, now.plusSeconds(30));
        DeliveryAttemptSessionCount nextCount = attempt.getAttemptCount().increment();
        attempt.scheduleNextRetry(now, now.plusSeconds(60), nextCount, "transient error");

        assertThat(attempt.getAttemptCount().value())
            .as("scheduleNextRetry must increment attempt_count")
            .isGreaterThan(0);

        // Ensure count can never go negative (VO guard)
        assertThatThrownBy(() -> new DeliveryAttemptSessionCount(-1))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void claimedBy_claimedUntil_synchronization() {
        // Invariant 3: claimed_by / claimed_until synchronization.
        // readyFor: both null. After claim: both non-null. After markDone/markFailed/scheduleNextRetry: both null.
        DeliveryId deliveryId = new DeliveryId(UUID.randomUUID());
        Instant now = fixedClock.instant();

        DeliveryAttempt attempt = DeliveryAttempt.readyFor(deliveryId, fixedClock);
        assertThat(attempt.getClaimedBy()).isNull();
        assertThat(attempt.getClaimedUntil()).isNull();

        attempt.claim("worker-42", now, now.plusSeconds(30));
        assertThat(attempt.getClaimedBy()).isNotNull();
        assertThat(attempt.getClaimedUntil()).isNotNull();

        attempt.markDone(now);
        assertThat(attempt.getClaimedBy()).isNull();
        assertThat(attempt.getClaimedUntil()).isNull();

        // markFailed also clears claim fields
        DeliveryAttempt attempt2 = DeliveryAttempt.readyFor(deliveryId, fixedClock);
        attempt2.claim("worker-43", now, now.plusSeconds(30));
        DeliveryAttemptSessionCount finalCount = attempt2.getAttemptCount().increment();
        attempt2.markFailed(finalCount, "permanent failure", now);
        assertThat(attempt2.getClaimedBy()).isNull();
        assertThat(attempt2.getClaimedUntil()).isNull();

        // scheduleNextRetry also clears claim fields
        DeliveryAttempt attempt3 = DeliveryAttempt.readyFor(deliveryId, fixedClock);
        attempt3.claim("worker-44", now, now.plusSeconds(30));
        attempt3.scheduleNextRetry(now, now.plusSeconds(60), attempt3.getAttemptCount().increment(), "retry");
        assertThat(attempt3.getClaimedBy()).isNull();
        assertThat(attempt3.getClaimedUntil()).isNull();
    }

    @Test
    void nextAttemptAt_greaterThanOrEqualToCurrentTime() {
        // Invariant 4: next_attempt_at ≥ current time — readyFor sets nextAttemptAt=now (≥ clock.instant()).
        DeliveryId deliveryId = new DeliveryId(UUID.randomUUID());
        Instant clockInstant = fixedClock.instant();

        DeliveryAttempt attempt = DeliveryAttempt.readyFor(deliveryId, fixedClock);

        assertThat(attempt.getNextAttemptAt())
            .as("nextAttemptAt must be >= now at creation")
            .isAfterOrEqualTo(clockInstant);
    }

    @Test
    void readyFor_initialAttemptCountIsZero() {
        // EXTRA invariant: CLAUDE.md — new delivery_attempt row starts from 0.
        DeliveryAttempt a = DeliveryAttempt.readyFor(new DeliveryId(UUID.randomUUID()), fixedClock);
        assertThat(a.getAttemptCount().value()).isZero();
    }
}
