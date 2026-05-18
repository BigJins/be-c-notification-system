package com.livenotification.delivery.adapter.in.scheduler;

import com.livenotification.delivery.application.DeliveryAttemptRepository;
import com.livenotification.delivery.application.metrics.DeliveryMetrics;
import com.livenotification.delivery.domain.DeliveryAttemptState;
import com.livenotification.global.config.NotificationProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class DeliveryAttemptCleanupWorker {

    private final DeliveryAttemptRepository repository;
    private final NotificationProperties properties;
    private final DeliveryMetrics metrics;
    private final Clock clock;

    /** Hourly cleanup of terminal-state delivery_attempt rows older than retention window. */
    @Scheduled(cron = "${notification.cleanup.delivery-attempt-cron:0 0 * * * *}")
    @Transactional
    public void cleanup() {
        try {
            Instant cutoff = clock.instant().minus(properties.cleanup().deliveryAttemptRetention());
            int deleted = repository.deleteByStateInAndUpdatedAtBefore(
                List.of(DeliveryAttemptState.DONE, DeliveryAttemptState.FAILED), cutoff);
            if (deleted > 0) {
                metrics.recordCleanupDeleted(deleted);
                log.info("delivery_attempt cleanup: deleted {} rows (cutoff={})", deleted, cutoff);
            }
        } catch (Exception e) {
            log.error("DeliveryAttemptCleanupWorker.cleanup failed", e);
        }
    }
}
