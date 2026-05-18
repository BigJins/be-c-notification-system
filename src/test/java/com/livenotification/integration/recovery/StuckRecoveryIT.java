package com.livenotification.integration.recovery;

import com.livenotification.integration.support.AbstractIntegrationTest;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that ReaperWorker releases IN_PROGRESS delivery_attempt rows whose
 * claimed_until is in the past (i.e. the worker that claimed them died).
 * reaper-interval is overridden to 500ms in application-test.yml.
 */
class StuckRecoveryIT extends AbstractIntegrationTest {

    @Test
    void stuckInProgress_attempt_recoveredByReaper() {
        UUID notificationId = UUID.randomUUID();
        UUID deliveryId     = UUID.randomUUID();
        UUID attemptId      = UUID.randomUUID();
        Instant past              = Instant.now().minusSeconds(120);
        Instant claimedUntilPast  = Instant.now().minusSeconds(60);

        jdbcTemplate.update("""
            INSERT INTO notification (id, event_id, recipient_id, type, payload, created_at, updated_at)
            VALUES (?, 'stuck-e1', 'u1', 'PAYMENT_CONFIRMED', '{"k":"v"}'::jsonb, ?, ?)
            """, notificationId, Timestamp.from(past), Timestamp.from(past));

        jdbcTemplate.update("""
            INSERT INTO delivery (id, notification_id, channel, state, attempt_count, created_at, updated_at)
            VALUES (?, ?, 'EMAIL', 'PENDING', 0, ?, ?)
            """, deliveryId, notificationId, Timestamp.from(past), Timestamp.from(past));

        jdbcTemplate.update("""
            INSERT INTO delivery_attempt (id, delivery_id, state, attempt_count, next_attempt_at,
                                          claimed_by, claimed_until, created_at, updated_at)
            VALUES (?, ?, 'IN_PROGRESS', 0, ?, 'worker-dead', ?, ?, ?)
            """, attemptId, deliveryId,
                Timestamp.from(past),
                Timestamp.from(claimedUntilPast),
                Timestamp.from(past), Timestamp.from(past));

        // ReaperWorker fires every 500ms; wait up to 5s for the reaper to release the stuck claim.
        // After release the DispatchWorker may immediately pick up the row (READY→IN_PROGRESS→DONE),
        // so we assert the invariant that the dead worker's claim is cleared:
        //   - state is no longer IN_PROGRESS with the old claimed_by
        //   - claimed_by is no longer 'worker-dead'
        Awaitility.await()
            .atMost(Duration.ofSeconds(5))
            .pollInterval(Duration.ofMillis(200))
            .untilAsserted(() -> {
                Map<String, Object> row = jdbcTemplate.queryForMap(
                    "SELECT state, claimed_by FROM delivery_attempt WHERE id = ?", attemptId);
                String state     = (String) row.get("state");
                Object claimedBy = row.get("claimed_by");

                // The reaper must have cleared the stale claim; 'worker-dead' must be gone.
                assertThat(claimedBy).as("claimed_by must be cleared by reaper").isNotEqualTo("worker-dead");
                // State must have advanced past the stuck IN_PROGRESS (READY, or already dispatched further).
                assertThat(state).as("state must have advanced from stuck IN_PROGRESS")
                    .isNotEqualTo("IN_PROGRESS");
            });
    }
}
