package com.livenotification.notification.domain;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity @Table(name = "notification")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Getter
@EqualsAndHashCode(of = "id")
@ToString(of = {"id", "eventId", "recipientId", "type"})
public class Notification {
    @Id private NotificationId id;
    @Column(name = "event_id",     updatable = false, nullable = false) private EventId eventId;
    @Column(name = "recipient_id", updatable = false, nullable = false) private RecipientId recipientId;
    @Enumerated(EnumType.STRING) @Column(updatable = false, nullable = false) private NotificationType type;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", columnDefinition = "jsonb", updatable = false, nullable = false)
    @Getter(AccessLevel.NONE)   // raw JsonNode getter hidden — VO accessor only
    private JsonNode payload;
    @Column(name = "read_at") private Instant readAt;
    @Column(name = "created_at", updatable = false, nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    /** Registration entry. Clock injected — application service passes it in. */
    public static Notification create(EventId eventId, RecipientId recipientId,
                                       NotificationType type, NotificationPayload payload,
                                       Clock clock) {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(recipientId, "recipientId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(payload, "payload");
        Instant now = clock.instant();
        Notification n = new Notification();
        n.id = new NotificationId(UUID.randomUUID());
        n.eventId = eventId; n.recipientId = recipientId; n.type = type;
        n.payload = payload.value();
        n.createdAt = now; n.updatedAt = now;
        return n;
    }

    /**
     * Simple state mutator. *Invariant #2 enforcement is application service's responsibility*:
     * NotificationService.markRead calls deliveryRepository.existsByNotificationIdAndChannelAndState(
     *   id, IN_APP, SENT) → false → throw ReadStateViolationException(422). entity doesn't cross-AR query.
     */
    public void markRead(Instant now) {
        if (this.readAt != null) return;   // idempotent
        this.readAt = now;
        this.updatedAt = now;
    }

    /** VO wrapper accessor. */
    public NotificationPayload payload() { return new NotificationPayload(this.payload); }
}
