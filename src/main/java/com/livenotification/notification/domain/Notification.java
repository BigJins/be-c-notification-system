package com.livenotification.notification.domain;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "notification")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Getter
@EqualsAndHashCode(of = "id")
@ToString(of = {"id", "eventId", "recipientId", "type"})
public class Notification {

    @Id
    @JdbcTypeCode(SqlTypes.UUID)
    @Getter(AccessLevel.NONE)
    private UUID id;

    @Column(name = "event_id", updatable = false, nullable = false)
    @Getter(AccessLevel.NONE)
    private String eventId;

    @Column(name = "recipient_id", updatable = false, nullable = false)
    @Getter(AccessLevel.NONE)
    private String recipientId;

    @Enumerated(EnumType.STRING)
    @Column(updatable = false, nullable = false)
    private NotificationType type;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", columnDefinition = "jsonb", updatable = false, nullable = false)
    @Getter(AccessLevel.NONE)
    private JsonNode payload;

    @Column(name = "read_at")
    private Instant readAt;

    @Column(name = "created_at", updatable = false, nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public NotificationId getId() {
        return new NotificationId(id);
    }

    public EventId getEventId() {
        return new EventId(eventId);
    }

    public RecipientId getRecipientId() {
        return new RecipientId(recipientId);
    }

    public static Notification create(EventId eventId, RecipientId recipientId,
                                      NotificationType type, NotificationPayload payload,
                                      Clock clock) {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(recipientId, "recipientId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(payload, "payload");

        Instant now = clock.instant();
        Notification notification = new Notification();
        notification.id = UUID.randomUUID();
        notification.eventId = eventId.value();
        notification.recipientId = recipientId.value();
        notification.type = type;
        notification.payload = payload.value();
        notification.createdAt = now;
        notification.updatedAt = now;
        return notification;
    }

    public void markRead(Instant now) {
        if (this.readAt != null) {
            return;
        }
        this.readAt = now;
        this.updatedAt = now;
    }

    public NotificationPayload payload() {
        return new NotificationPayload(this.payload);
    }
}
