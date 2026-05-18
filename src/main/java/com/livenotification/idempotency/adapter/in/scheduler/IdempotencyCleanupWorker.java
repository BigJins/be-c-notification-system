package com.livenotification.idempotency.adapter.in.scheduler;

import com.livenotification.idempotency.application.IdempotencyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

@Component
@RequiredArgsConstructor
@Slf4j
public class IdempotencyCleanupWorker {

    private final IdempotencyRepository repository;
    private final Clock clock;

    /** Every 10 minutes, delete expired idempotency_record rows. */
    @Scheduled(cron = "${notification.cleanup.idempotency-cron:0 */10 * * * *}")
    @Transactional
    public void cleanup() {
        try {
            int deleted = repository.deleteExpired(clock.instant());
            if (deleted > 0) {
                log.info("idempotency cleanup: deleted {} expired rows", deleted);
            }
        } catch (Exception e) {
            log.error("IdempotencyCleanupWorker.cleanup failed", e);
        }
    }
}
