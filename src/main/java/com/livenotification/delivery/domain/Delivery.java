package com.livenotification.delivery.domain;

import com.livenotification.notification.domain.NotificationId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "delivery")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Getter
@EqualsAndHashCode(of = "id")
@ToString(of = {"id", "notificationId", "channel", "state"})
public class Delivery {

    @Id
    @JdbcTypeCode(SqlTypes.UUID)
    @Getter(AccessLevel.NONE)
    private UUID id;

    @Column(name = "notification_id", updatable = false, nullable = false)
    @JdbcTypeCode(SqlTypes.UUID)
    @Getter(AccessLevel.NONE)
    private UUID notificationId;

    @Enumerated(EnumType.STRING)
    @Column(updatable = false, nullable = false)
    private ChannelType channel;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DeliveryState state;

    @Column(name = "attempt_count", nullable = false)
    @Getter(AccessLevel.NONE)
    private int attemptCount;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "created_at", updatable = false, nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public DeliveryId getId() {
        return new DeliveryId(id);
    }

    public NotificationId getNotificationId() {
        return new NotificationId(notificationId);
    }

    public DeliveryAttemptCount getAttemptCount() {
        return new DeliveryAttemptCount(attemptCount);
    }

    public static Delivery forEmail(NotificationId notificationId, Clock clock) {
        Instant now = clock.instant();
        Delivery delivery = new Delivery();
        delivery.id = UUID.randomUUID();
        delivery.notificationId = notificationId.value();
        delivery.channel = ChannelType.EMAIL;
        delivery.state = DeliveryState.PENDING;
        delivery.attemptCount = 0;
        delivery.createdAt = now;
        delivery.updatedAt = now;
        return delivery;
    }

    public static Delivery forInApp(NotificationId notificationId, Clock clock) {
        Instant now = clock.instant();
        Delivery delivery = new Delivery();
        delivery.id = UUID.randomUUID();
        delivery.notificationId = notificationId.value();
        delivery.channel = ChannelType.IN_APP;
        delivery.state = DeliveryState.SENT;
        delivery.attemptCount = 1;
        delivery.sentAt = now;
        delivery.createdAt = now;
        delivery.updatedAt = now;
        return delivery;
    }

    public void markSent(Instant now) {
        guardChannelEmail();
        guardState(DeliveryState.PENDING);
        this.state = DeliveryState.SENT;
        this.sentAt = now;
        this.attemptCount += 1;
        this.updatedAt = now;
    }

    public void markDead(String reason, Instant now) {
        guardChannelEmail();
        guardState(DeliveryState.PENDING);
        this.state = DeliveryState.DEAD;
        this.lastError = reason;
        this.attemptCount += 1;
        this.updatedAt = now;
    }

    public void markPending(Instant now) {
        guardChannelEmail();
        guardState(DeliveryState.DEAD);
        this.state = DeliveryState.PENDING;
        this.updatedAt = now;
    }

    public void recordTransientFailure(String reason, Instant now) {
        guardChannelEmail();
        this.lastError = reason;
        this.attemptCount += 1;
        this.updatedAt = now;
    }

    private void guardChannelEmail() {
        if (this.channel != ChannelType.EMAIL) {
            throw new IllegalStateException("EMAIL only: " + this.channel);
        }
    }

    private void guardState(DeliveryState expected) {
        if (this.state != expected) {
            throw new IllegalStateException("expected " + expected + " but was " + this.state);
        }
    }
}
