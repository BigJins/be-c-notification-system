package com.livenotification.integration.recovery;

import com.livenotification.delivery.adapter.in.scheduler.DispatchWorker;
import com.livenotification.integration.support.AbstractIntegrationTest;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Persisted queue durability test.
 * A READY delivery_attempt inserted directly into Postgres must be picked up by
 * the worker on a later tick, proving dispatch resumes from persisted DB state.
 */
class DurabilityIT extends AbstractIntegrationTest {

    @Autowired DispatchWorker dispatchWorker;

    @Test
    void persistedReadyAttempt_isDispatchedOnLaterWorkerTick() {
        UUID notificationId = UUID.randomUUID();
        UUID deliveryId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        Instant now = Instant.now();
        Instant due = now.minusSeconds(5);

        jdbcTemplate.update("""
            INSERT INTO notification (id, event_id, recipient_id, type, payload, created_at, updated_at)
            VALUES (?, 'dur-e1', 'u-dur', 'PAYMENT_CONFIRMED',
                    '{"subject":"resume","body":"from-db"}'::jsonb, ?, ?)
            """, notificationId, Timestamp.from(now), Timestamp.from(now));

        jdbcTemplate.update("""
            INSERT INTO delivery (id, notification_id, channel, state, attempt_count, created_at, updated_at)
            VALUES (?, ?, 'EMAIL', 'PENDING', 0, ?, ?)
            """, deliveryId, notificationId, Timestamp.from(now), Timestamp.from(now));

        jdbcTemplate.update("""
            INSERT INTO delivery_attempt (id, delivery_id, state, attempt_count, next_attempt_at, created_at, updated_at)
            VALUES (?, ?, 'READY', 0, ?, ?, ?)
            """, attemptId, deliveryId, Timestamp.from(due), Timestamp.from(now), Timestamp.from(now));

        dispatchWorker.tick();

        Awaitility.await()
            .atMost(Duration.ofSeconds(3))
            .pollInterval(Duration.ofMillis(150))
            .untilAsserted(() -> {
                Map<String, Object> delivery = jdbcTemplate.queryForMap(
                    "SELECT state, attempt_count, sent_at FROM delivery WHERE id = ?",
                    deliveryId);
                assertThat(delivery.get("state")).isEqualTo("SENT");
                assertThat(((Number) delivery.get("attempt_count")).intValue()).isEqualTo(1);
                assertThat(delivery.get("sent_at")).isNotNull();

                Map<String, Object> attempt = jdbcTemplate.queryForMap(
                    "SELECT state, attempt_count FROM delivery_attempt WHERE id = ?",
                    attemptId);
                assertThat(attempt.get("state")).isEqualTo("DONE");
                assertThat(((Number) attempt.get("attempt_count")).intValue()).isEqualTo(1);
            });
    }
}
