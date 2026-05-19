package com.livenotification.integration.cleanup;

import com.livenotification.delivery.adapter.in.scheduler.DeliveryAttemptCleanupWorker;
import com.livenotification.integration.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DeliveryAttemptCleanupIT extends AbstractIntegrationTest {

    @Autowired DeliveryAttemptCleanupWorker cleanupWorker;

    @Test
    void cleanup_deletesOnlyTerminalAttemptsOlderThanRetention() {
        Instant now = Instant.now();
        Instant old = now.minusSeconds(31L * 24 * 60 * 60);
        Instant recent = now.minusSeconds(60);

        UUID notificationId = UUID.randomUUID();
        UUID deliveryId = UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO notification (id, event_id, recipient_id, type, payload, created_at, updated_at)
            VALUES (?, 'cleanup-da-1', 'u-clean', 'PAYMENT_CONFIRMED', '{}'::jsonb, ?, ?)
            """, notificationId, Timestamp.from(old), Timestamp.from(old));
        jdbcTemplate.update("""
            INSERT INTO delivery (id, notification_id, channel, state, attempt_count, created_at, updated_at)
            VALUES (?, ?, 'EMAIL', 'PENDING', 0, ?, ?)
            """, deliveryId, notificationId, Timestamp.from(old), Timestamp.from(old));

        UUID oldDone = UUID.randomUUID();
        UUID oldFailed = UUID.randomUUID();
        UUID oldReady = UUID.randomUUID();
        UUID recentDone = UUID.randomUUID();

        insertAttempt(oldDone, deliveryId, "DONE", old, old);
        insertAttempt(oldFailed, deliveryId, "FAILED", old, old);
        insertAttempt(oldReady, deliveryId, "READY", old, old);
        insertAttempt(recentDone, deliveryId, "DONE", recent, recent);

        cleanupWorker.cleanup();

        assertThat(countAttempt(oldDone)).isZero();
        assertThat(countAttempt(oldFailed)).isZero();
        assertThat(countAttempt(oldReady)).isEqualTo(1);
        assertThat(countAttempt(recentDone)).isEqualTo(1);
    }

    private void insertAttempt(UUID id, UUID deliveryId, String state, Instant nextAttemptAt, Instant updatedAt) {
        jdbcTemplate.update("""
            INSERT INTO delivery_attempt (id, delivery_id, state, attempt_count, next_attempt_at, created_at, updated_at)
            VALUES (?, ?, ?, 1, ?, ?, ?)
            """, id, deliveryId, state, Timestamp.from(nextAttemptAt), Timestamp.from(updatedAt), Timestamp.from(updatedAt));
    }

    private int countAttempt(UUID id) {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM delivery_attempt WHERE id = ?",
            Integer.class, id);
        return count == null ? 0 : count;
    }
}
