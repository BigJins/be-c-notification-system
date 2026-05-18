package com.livenotification.delivery.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name = "delivery_attempt")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Getter @EqualsAndHashCode(of = "id") @ToString(of = {"id", "deliveryId", "state", "attemptCount"})
public class DeliveryAttempt {
    @Id private DeliveryAttemptId id;
    @Column(name = "delivery_id", updatable = false, nullable = false) private DeliveryId deliveryId;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private DeliveryAttemptState state;
    @Column(name = "attempt_count", nullable = false) private DeliveryAttemptSessionCount attemptCount;
    @Column(name = "next_attempt_at") private Instant nextAttemptAt;
    @Column(name = "claimed_by") private String claimedBy;
    @Column(name = "claimed_until") private Instant claimedUntil;
    @Column(name = "last_error") private String lastError;
    @Column(name = "created_at", updatable = false, nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    /**
     * EMAIL worker queue entry — READY, attemptCount=0, nextAttemptAt=now (immediate poll).
     * claimedBy/Until=null.
     */
    public static DeliveryAttempt readyFor(DeliveryId deliveryId, Clock clock) {
        Instant now = clock.instant();
        DeliveryAttempt a = new DeliveryAttempt();
        a.id = new DeliveryAttemptId(UUID.randomUUID());
        a.deliveryId = deliveryId;
        a.state = DeliveryAttemptState.READY;
        a.attemptCount = DeliveryAttemptSessionCount.zero();
        a.nextAttemptAt = now;
        a.createdAt = now; a.updatedAt = now;
        return a;
    }

    /**
     * IN_APP audit entry — DONE, attemptCount=1, nextAttemptAt=now.
     * Created in the same transaction as the SENT Delivery (IN_APP optimization).
     */
    public static DeliveryAttempt completedFor(DeliveryId deliveryId, Clock clock) {
        Instant now = clock.instant();
        DeliveryAttempt a = new DeliveryAttempt();
        a.id = new DeliveryAttemptId(UUID.randomUUID());
        a.deliveryId = deliveryId;
        a.state = DeliveryAttemptState.DONE;
        a.attemptCount = DeliveryAttemptSessionCount.zero().increment();
        a.nextAttemptAt = now;
        a.createdAt = now; a.updatedAt = now;
        return a;
    }

    /** READY → IN_PROGRESS. Worker claims this attempt. */
    public void claim(String workerId, Instant now, Instant claimedUntil) {
        guardState(DeliveryAttemptState.READY);
        this.state = DeliveryAttemptState.IN_PROGRESS;
        this.claimedBy = workerId;
        this.claimedUntil = claimedUntil;
        this.updatedAt = now;
    }

    /** IN_PROGRESS → DONE. Clear claim fields. */
    public void markDone(Instant now) {
        guardState(DeliveryAttemptState.IN_PROGRESS);
        this.state = DeliveryAttemptState.DONE;
        this.claimedBy = null;
        this.claimedUntil = null;
        this.updatedAt = now;
    }

    /** IN_PROGRESS → FAILED. Set final attemptCount + reason. Clear claim fields. */
    public void markFailed(DeliveryAttemptSessionCount finalCount, String reason, Instant now) {
        guardState(DeliveryAttemptState.IN_PROGRESS);
        this.state = DeliveryAttemptState.FAILED;
        this.attemptCount = finalCount;
        this.lastError = reason;
        this.claimedBy = null;
        this.claimedUntil = null;
        this.updatedAt = now;
    }

    /**
     * IN_PROGRESS → READY (transient failure, schedule retry).
     * nextAttemptAt and nextCount set by caller (RetryPolicy decides).
     */
    public void scheduleNextRetry(Instant now, Instant nextAttemptAt,
                                   DeliveryAttemptSessionCount nextCount, String reason) {
        guardState(DeliveryAttemptState.IN_PROGRESS);
        this.state = DeliveryAttemptState.READY;
        this.nextAttemptAt = nextAttemptAt;
        this.attemptCount = nextCount;
        this.lastError = reason;
        this.claimedBy = null;
        this.claimedUntil = null;
        this.updatedAt = now;
    }

    private void guardState(DeliveryAttemptState expected) {
        if (this.state != expected)
            throw new IllegalStateException("expected " + expected + " but was " + this.state);
    }
}
