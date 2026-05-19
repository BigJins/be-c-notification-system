package com.livenotification.delivery.adapter.in.scheduler;

import com.livenotification.delivery.application.DeliveryRelayService;
import com.livenotification.delivery.domain.DeliveryAttemptId;
import com.livenotification.global.config.NotificationProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
@Slf4j
public class DispatchWorker {

    private final DeliveryRelayService relayService;
    private final NotificationProperties properties;
    private final Semaphore semaphore;
    private final ExecutorService virtualThreadExecutor;

    @Scheduled(fixedDelayString = "${notification.worker.poll-interval}")
    public void tick() {
        try {
            int availablePermits = semaphore.availablePermits();
            if (availablePermits <= 0) {
                log.debug("dispatch tick skipped - no semaphore permits available");
                return;
            }
            List<DeliveryAttemptId> claimed = relayService.claimBatch(
                Math.min(properties.worker().batchSize(), availablePermits),
                workerId(),
                properties.worker().claimLease());
            for (DeliveryAttemptId id : claimed) {
                dispatchAsync(id);
            }
        } catch (Exception e) {
            log.error("DispatchWorker.tick failed", e);
        }
    }

    /**
     * Semaphore permit ownership transfer pattern:
     * - tryAcquire succeeds → acquired=true
     * - submit succeeds → ownership transferred to the task; reset acquired=false so outer finally doesn't double-release
     * - submit throws RejectedExecutionException → outer finally releases (acquired is still true)
     * - InterruptedException → restore interrupt status, log + return
     */
    private void dispatchAsync(DeliveryAttemptId id) {
        boolean acquired = false;
        try {
            acquired = semaphore.tryAcquire(1, TimeUnit.SECONDS);
            if (!acquired) {
                log.warn("dispatch skipped — semaphore exhausted, attemptId={}", id.value());
                return;
            }
            virtualThreadExecutor.submit(() -> {
                try {
                    relayService.relay(id);
                } catch (Exception e) {
                    log.error("relay failed attemptId={}", id.value(), e);
                } finally {
                    semaphore.release();
                }
            });
            acquired = false;   // ownership transferred to the submitted task
        } catch (RejectedExecutionException e) {
            log.error("dispatch submit rejected, attemptId={}", id.value(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            if (acquired) semaphore.release();   // leak guard
        }
    }

    private String workerId() {
        String hostname = System.getenv("HOSTNAME");
        if (hostname != null && !hostname.isBlank()) return hostname;
        return "worker-" + ProcessHandle.current().pid();
    }
}
