package com.livenotification.delivery.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "delivery_attempt")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Getter
@EqualsAndHashCode(of = "id")
@ToString(of = {"id", "deliveryId", "state", "attemptCount"})
public class DeliveryAttempt {

    @Id
    @JdbcTypeCode(SqlTypes.UUID)
    @Getter(AccessLevel.NONE)
    private UUID id;

    @Column(name = "delivery_id", updatable = false, nullable = false)
    @JdbcTypeCode(SqlTypes.UUID)
    @Getter(AccessLevel.NONE)
    private UUID deliveryId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DeliveryAttemptState state;

    @Column(name = "attempt_count", nullable = false)
    @Getter(AccessLevel.NONE)
    private int attemptCount;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "claimed_by")
    private String claimedBy;

    @Column(name = "claimed_until")
    private Instant claimedUntil;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "created_at", updatable = false, nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public DeliveryAttemptId getId() {
        return new DeliveryAttemptId(id);
    }

    public DeliveryId getDeliveryId() {
        return new DeliveryId(deliveryId);
    }

    public DeliveryAttemptSessionCount getAttemptCount() {
        return new DeliveryAttemptSessionCount(attemptCount);
    }

    public static DeliveryAttempt readyFor(DeliveryId deliveryId, Clock clock) {
        Instant now = clock.instant();
        DeliveryAttempt attempt = new DeliveryAttempt();
        attempt.id = UUID.randomUUID();
        attempt.deliveryId = deliveryId.value();
        attempt.state = DeliveryAttemptState.READY;
        attempt.attemptCount = 0;
        attempt.nextAttemptAt = now;
        attempt.createdAt = now;
        attempt.updatedAt = now;
        return attempt;
    }

    public static DeliveryAttempt completedFor(DeliveryId deliveryId, Clock clock) {
        Instant now = clock.instant();
        DeliveryAttempt attempt = new DeliveryAttempt();
        attempt.id = UUID.randomUUID();
        attempt.deliveryId = deliveryId.value();
        attempt.state = DeliveryAttemptState.DONE;
        attempt.attemptCount = 1;
        attempt.nextAttemptAt = now;
        attempt.createdAt = now;
        attempt.updatedAt = now;
        return attempt;
    }

    public void claim(String workerId, Instant now, Instant claimedUntil) {
        guardState(DeliveryAttemptState.READY);
        this.state = DeliveryAttemptState.IN_PROGRESS;
        this.claimedBy = workerId;
        this.claimedUntil = claimedUntil;
        this.updatedAt = now;
    }

    public void markDone(DeliveryAttemptSessionCount finalCount, Instant now) {
        guardState(DeliveryAttemptState.IN_PROGRESS);
        this.state = DeliveryAttemptState.DONE;
        this.attemptCount = finalCount.value();
        this.claimedBy = null;
        this.claimedUntil = null;
        this.updatedAt = now;
    }

    public void markFailed(DeliveryAttemptSessionCount finalCount, String reason, Instant now) {
        guardState(DeliveryAttemptState.IN_PROGRESS);
        this.state = DeliveryAttemptState.FAILED;
        this.attemptCount = finalCount.value();
        this.lastError = reason;
        this.claimedBy = null;
        this.claimedUntil = null;
        this.updatedAt = now;
    }

    public void scheduleNextRetry(Instant now, Instant nextAttemptAt,
                                  DeliveryAttemptSessionCount nextCount, String reason) {
        guardState(DeliveryAttemptState.IN_PROGRESS);
        this.state = DeliveryAttemptState.READY;
        this.nextAttemptAt = nextAttemptAt;
        this.attemptCount = nextCount.value();
        this.lastError = reason;
        this.claimedBy = null;
        this.claimedUntil = null;
        this.updatedAt = now;
    }

    private void guardState(DeliveryAttemptState expected) {
        if (this.state != expected) {
            throw new IllegalStateException("expected " + expected + " but was " + this.state);
        }
    }
}
