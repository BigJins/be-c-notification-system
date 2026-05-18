package com.livenotification.delivery.domain;

import com.livenotification.notification.domain.NotificationId;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name = "delivery")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Getter @EqualsAndHashCode(of = "id") @ToString(of = {"id", "notificationId", "channel", "state"})
public class Delivery {
    @Id private DeliveryId id;
    @Column(name = "notification_id", updatable = false, nullable = false) private NotificationId notificationId;
    @Enumerated(EnumType.STRING) @Column(updatable = false, nullable = false) private ChannelType channel;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private DeliveryState state;
    @Column(name = "attempt_count", nullable = false) private DeliveryAttemptCount attemptCount;
    @Column(name = "last_error") private String lastError;
    @Column(name = "sent_at") private Instant sentAt;
    @Column(name = "created_at", updatable = false, nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    /** EMAIL — PENDING entry. attempt_count=0, sentAt=null. */
    public static Delivery forEmail(NotificationId notificationId, Clock clock) {
        Instant now = clock.instant();
        Delivery d = new Delivery();
        d.id = new DeliveryId(UUID.randomUUID());
        d.notificationId = notificationId;
        d.channel = ChannelType.EMAIL;
        d.state = DeliveryState.PENDING;
        d.attemptCount = DeliveryAttemptCount.zero();
        d.createdAt = now; d.updatedAt = now;
        return d;
    }

    /** IN_APP — immediately SENT (invariant #4). attempt_count=1, sentAt=now. */
    public static Delivery forInApp(NotificationId notificationId, Clock clock) {
        Instant now = clock.instant();
        Delivery d = new Delivery();
        d.id = new DeliveryId(UUID.randomUUID());
        d.notificationId = notificationId;
        d.channel = ChannelType.IN_APP;
        d.state = DeliveryState.SENT;
        d.attemptCount = DeliveryAttemptCount.zero().increment();
        d.sentAt = now;
        d.createdAt = now; d.updatedAt = now;
        return d;
    }

    /** PENDING → SENT (EMAIL only). */
    public void markSent(Instant now) {
        guardChannelEmail();
        guardState(DeliveryState.PENDING);
        this.state = DeliveryState.SENT;
        this.sentAt = now;
        this.attemptCount = this.attemptCount.increment();
        this.updatedAt = now;
    }

    /** PENDING → DEAD. */
    public void markDead(String reason, Instant now) {
        guardChannelEmail();
        guardState(DeliveryState.PENDING);
        this.state = DeliveryState.DEAD;
        this.lastError = reason;
        this.attemptCount = this.attemptCount.increment();
        this.updatedAt = now;
    }

    /** DEAD → PENDING (admin retry). attempt_count preserved (invariant #2). */
    public void markPending(Instant now) {
        guardChannelEmail();
        guardState(DeliveryState.DEAD);
        this.state = DeliveryState.PENDING;
        this.updatedAt = now;
        // attemptCount NEVER reset — cumulative semantics
    }

    /**
     * Transient failure — *Delivery.attemptCount* (cumulative, per-channel) only increments.
     * application service also calls DeliveryAttempt.scheduleNextRetry, but
     * DeliveryAttempt.attemptCount is *per-session count* (each new row starts from 0).
     */
    public void recordTransientFailure(String reason, Instant now) {
        guardChannelEmail();
        this.lastError = reason;
        this.attemptCount = this.attemptCount.increment();
        this.updatedAt = now;
    }

    private void guardChannelEmail() {
        if (this.channel != ChannelType.EMAIL)
            throw new IllegalStateException("EMAIL only: " + this.channel);
    }
    private void guardState(DeliveryState expected) {
        if (this.state != expected)
            throw new IllegalStateException("expected " + expected + " but was " + this.state);
    }
}
