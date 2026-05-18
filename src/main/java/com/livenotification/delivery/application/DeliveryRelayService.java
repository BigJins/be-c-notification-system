package com.livenotification.delivery.application;

import com.livenotification.delivery.adapter.out.channel.ChannelRouter;
import com.livenotification.delivery.application.port.ChannelAdapter;
import com.livenotification.delivery.domain.Delivery;
import com.livenotification.delivery.domain.DeliveryAttempt;
import com.livenotification.delivery.domain.DeliveryAttemptId;
import com.livenotification.delivery.domain.DeliveryAttemptSessionCount;
import com.livenotification.delivery.domain.DeliveryState;
import com.livenotification.delivery.domain.DispatchResult;
import com.livenotification.delivery.domain.RetryPolicy;
import com.livenotification.global.config.NotificationProperties;
import com.livenotification.notification.application.NotificationLookup;
import com.livenotification.notification.application.NotificationView;
import com.livenotification.notification.domain.NotificationId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
@RequiredArgsConstructor
@Slf4j
public class DeliveryRelayService {

    private final DeliveryRepository deliveryRepository;
    private final DeliveryAttemptRepository attemptRepository;
    private final ChannelRouter channelRouter;
    private final NotificationLookup notificationLookup;
    private final RetryPolicy retryPolicy;
    private final NotificationProperties properties;
    private final ExecutorService virtualThreadExecutor;
    private final Clock clock;

    /**
     * Claim a batch of READY attempts in their own transaction so the lock
     * scope is small and DispatchWorker can submit each row's relay async.
     */
    @Transactional
    public List<DeliveryAttemptId> claimBatch(int batchSize, String workerId, Duration claimLease) {
        List<UUID> ids = attemptRepository.findClaimableIds(batchSize);
        if (ids.isEmpty()) return List.of();

        Instant now = clock.instant();
        Instant until = now.plus(claimLease);
        UUID[] arr = ids.toArray(UUID[]::new);
        int claimed = attemptRepository.claimByIds(arr, workerId, until, now);
        log.debug("claimBatch worker={} requested={} claimed={}", workerId, ids.size(), claimed);

        return Arrays.stream(arr).map(DeliveryAttemptId::new).toList();
    }

    /**
     * Run one delivery attempt end-to-end in its own transaction:
     * - load attempt + delivery + notification view
     * - call channel adapter with dispatch-timeout wrapping
     * - update state (Success | Transient retry | Transient max → Dead | Permanent → Dead)
     */
    @Transactional
    public void relay(DeliveryAttemptId attemptId) {
        DeliveryAttempt attempt = attemptRepository.findById(attemptId.value())
            .orElseThrow(() -> new IllegalStateException("attempt not found: " + attemptId));
        Delivery delivery = deliveryRepository.findById(attempt.getDeliveryId().value())
            .orElseThrow(() -> new IllegalStateException("delivery not found: " + attempt.getDeliveryId()));
        NotificationView notification = notificationLookup
            .findById(new NotificationId(delivery.getNotificationId().value()))
            .orElseThrow(() -> new IllegalStateException(
                "notification view not found: " + delivery.getNotificationId()));

        DispatchResult result = invokeAdapter(notification, delivery);
        Instant now = clock.instant();

        switch (result) {
            case DispatchResult.Success ignored -> {
                attempt.markDone(now);
                delivery.markSent(now);
            }
            case DispatchResult.PermanentFailure perm -> {
                attempt.markFailed(attempt.getAttemptCount().increment(), perm.reason(), now);
                delivery.markDead(perm.reason(), now);
            }
            case DispatchResult.TransientFailure trans -> {
                DeliveryAttemptSessionCount nextCount = attempt.getAttemptCount().increment();
                if (retryPolicy.shouldDead(nextCount, trans)) {
                    attempt.markFailed(nextCount, trans.reason(), now);
                    delivery.markDead(trans.reason(), now);
                } else {
                    Instant nextAttemptAt = retryPolicy.nextAttemptAt(nextCount, clock);
                    attempt.scheduleNextRetry(now, nextAttemptAt, nextCount, trans.reason());
                    delivery.recordTransientFailure(trans.reason(), now);
                }
            }
        }
    }

    /** Reaper: release claims that have passed claimed_until. */
    @Transactional
    public int releaseExpiredClaims() {
        Instant now = clock.instant();
        List<DeliveryAttempt> stuck = attemptRepository.findStuckAttempts(now);
        if (!stuck.isEmpty()) {
            attemptRepository.releaseStuck(now);
        }
        return stuck.size();
    }

    private DispatchResult invokeAdapter(NotificationView notification, Delivery delivery) {
        ChannelAdapter adapter = channelRouter.route(delivery.getChannel());
        Duration timeout = properties.worker().dispatchTimeout();
        try {
            return CompletableFuture
                .supplyAsync(() -> adapter.send(notification, delivery), virtualThreadExecutor)
                .get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            return new DispatchResult.TransientFailure("dispatch timeout after " + timeout, e);
        } catch (ExecutionException e) {
            return classifyException(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new DispatchResult.TransientFailure("interrupted", e);
        }
    }

    private DispatchResult classifyException(Throwable cause) {
        if (cause instanceof IllegalArgumentException)
            return new DispatchResult.PermanentFailure("invalid input", cause);
        return new DispatchResult.TransientFailure("unclassified runtime error", cause);
    }
}
