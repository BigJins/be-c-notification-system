package com.livenotification.integration.dedup;

import com.livenotification.integration.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeliveryPerChannelDedupIT extends AbstractIntegrationTest {

    @Test
    void uniqueDeliveryPerChannel_isEnforced() {
        UUID notificationId = UUID.randomUUID();
        Instant now = Instant.now();

        jdbcTemplate.update("""
            INSERT INTO notification (id, event_id, recipient_id, type, payload, created_at, updated_at)
            VALUES (?, 'e-uq', 'u-uq', 'PAYMENT_CONFIRMED', '{}'::jsonb, ?, ?)
            """, notificationId, Timestamp.from(now), Timestamp.from(now));

        UUID d1Id = UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO delivery (id, notification_id, channel, state, attempt_count, created_at, updated_at)
            VALUES (?, ?, 'EMAIL', 'PENDING', 0, ?, ?)
            """, d1Id, notificationId, Timestamp.from(now), Timestamp.from(now));

        UUID d2Id = UUID.randomUUID();
        assertThatThrownBy(() -> jdbcTemplate.update("""
            INSERT INTO delivery (id, notification_id, channel, state, attempt_count, created_at, updated_at)
            VALUES (?, ?, 'EMAIL', 'PENDING', 0, ?, ?)
            """, d2Id, notificationId, Timestamp.from(now), Timestamp.from(now)))
            .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
    }
}
