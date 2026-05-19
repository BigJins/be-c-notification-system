package com.livenotification.notification.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.livenotification.notification.domain.Notification;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@Component
@RequiredArgsConstructor
class NotificationRegistrationStore {

    private final NotificationRepository notificationRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final NamedParameterJdbcTemplate jdbcTemplate;

    NotificationInsertResult insertOrLoad(RegisterCommand cmd) {
        Instant now = clock.instant();
        UUID candidateId = UUID.randomUUID();
        UUID insertedId = insertIfAbsent(candidateId, cmd, now);

        if (insertedId != null) {
            Notification inserted = notificationRepository
                .findByEventIdAndRecipientIdAndType(cmd.eventId(), cmd.recipientId(), cmd.type())
                .orElseThrow(() -> new IllegalStateException("inserted notification missing: " + insertedId));
            return new NotificationInsertResult(inserted, true);
        }

        Notification existing = notificationRepository
            .findByEventIdAndRecipientIdAndType(cmd.eventId(), cmd.recipientId(), cmd.type())
            .orElseThrow(() -> new IllegalStateException("existing notification missing after dedup conflict"));
        return new NotificationInsertResult(existing, false);
    }

    private String serializePayload(RegisterCommand cmd) {
        try {
            return objectMapper.writeValueAsString(cmd.payload().value());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("payload serialization failed", e);
        }
    }

    private UUID insertIfAbsent(UUID candidateId, RegisterCommand cmd, Instant now) {
        MapSqlParameterSource params = new MapSqlParameterSource()
            .addValue("id", candidateId)
            .addValue("eventId", cmd.eventId().value())
            .addValue("recipientId", cmd.recipientId().value())
            .addValue("type", cmd.type().name())
            .addValue("payload", serializePayload(cmd))
            .addValue("createdAt", Timestamp.from(now))
            .addValue("updatedAt", Timestamp.from(now));

        return jdbcTemplate.query("""
            INSERT INTO notification (id, event_id, recipient_id, type, payload, created_at, updated_at)
            VALUES (:id, :eventId, :recipientId, :type, CAST(:payload AS jsonb), :createdAt, :updatedAt)
            ON CONFLICT (event_id, recipient_id, type) DO NOTHING
            RETURNING id
            """, params, rs -> rs.next() ? (UUID) rs.getObject("id") : null);
    }
}
