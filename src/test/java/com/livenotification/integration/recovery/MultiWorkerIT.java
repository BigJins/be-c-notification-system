package com.livenotification.integration.recovery;

import com.livenotification.delivery.application.DeliveryRelayService;
import com.livenotification.delivery.domain.DeliveryAttemptId;
import com.livenotification.integration.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies SKIP LOCKED prevents double-claiming across two concurrent workers.
 *
 * Two threads race claimBatch() via DeliveryRelayService. The ConcurrentHashMap
 * tracks every claimed ID; a double-claim would result in a non-null previous value.
 * The live DispatchWorker may also run concurrently — we assert only that no ID
 * is claimed by more than one caller (zero-tolerance for double-claim).
 */
class MultiWorkerIT extends AbstractIntegrationTest {

    @Autowired DeliveryRelayService relayService;

    @Test
    void skipLocked_preventsDoubleClaim() throws Exception {
        int n = 30;
        Instant now = Instant.now();
        // next_attempt_at in the past so the READY rows are immediately claimable.
        Instant due = now.minusSeconds(1);

        for (int i = 0; i < n; i++) {
            UUID nid = UUID.randomUUID();
            UUID did = UUID.randomUUID();
            UUID aid = UUID.randomUUID();

            jdbcTemplate.update("""
                INSERT INTO notification (id, event_id, recipient_id, type, payload, created_at, updated_at)
                VALUES (?, ?, 'u-mw', 'PAYMENT_CONFIRMED', '{}'::jsonb, ?, ?)
                """, nid, "mwsl-e-" + i, Timestamp.from(now), Timestamp.from(now));

            jdbcTemplate.update("""
                INSERT INTO delivery (id, notification_id, channel, state, attempt_count, created_at, updated_at)
                VALUES (?, ?, 'EMAIL', 'PENDING', 0, ?, ?)
                """, did, nid, Timestamp.from(now), Timestamp.from(now));

            jdbcTemplate.update("""
                INSERT INTO delivery_attempt (id, delivery_id, state, attempt_count, next_attempt_at, created_at, updated_at)
                VALUES (?, ?, 'READY', 0, ?, ?, ?)
                """, aid, did, Timestamp.from(due), Timestamp.from(now), Timestamp.from(now));
        }

        // Both threads claim in small batches; track every returned ID in a concurrent map.
        // A double-claim occurs if put() returns a non-null value (key already present).
        ConcurrentHashMap<UUID, String> claimedMap = new ConcurrentHashMap<>();
        AtomicInteger doubleClaim = new AtomicInteger();

        CountDownLatch startGun = new CountDownLatch(1);

        Callable<Void> task = () -> {
            startGun.await();
            String workerName = Thread.currentThread().getName();
            // Poll until no more rows are returned (or 20 iterations max).
            for (int i = 0; i < 20; i++) {
                List<DeliveryAttemptId> got = relayService.claimBatch(
                    10, workerName, java.time.Duration.ofSeconds(30));
                if (got.isEmpty()) break;
                for (DeliveryAttemptId id : got) {
                    String prev = claimedMap.put(id.value(), workerName);
                    if (prev != null) {
                        doubleClaim.incrementAndGet();
                    }
                }
            }
            return null;
        };

        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<Void> futA = pool.submit(() -> { Thread.currentThread().setName("worker-A"); return task.call(); });
        Future<Void> futB = pool.submit(() -> { Thread.currentThread().setName("worker-B"); return task.call(); });

        startGun.countDown();   // release both threads simultaneously

        pool.shutdown();
        boolean finished = pool.awaitTermination(20, TimeUnit.SECONDS);
        assertThat(finished).as("workers must finish within 20s").isTrue();

        // Re-throw any worker exception so the test reports the real cause.
        futA.get();
        futB.get();

        assertThat(doubleClaim.get())
            .as("SKIP LOCKED must prevent any double-claim across concurrent workers")
            .isZero();
    }
}
