package com.livenotification.admin.application;

import com.livenotification.delivery.application.DeliveryRepository;
import com.livenotification.delivery.application.DeliveryRetryRegistrar;
import com.livenotification.delivery.application.metrics.DeliveryMetrics;
import com.livenotification.delivery.domain.Delivery;
import com.livenotification.delivery.domain.DeliveryState;
import com.livenotification.notification.application.exception.NoRetriableDeliveryException;
import com.livenotification.notification.domain.NotificationId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AdminRetryService {

    private final DeliveryRepository deliveryRepository;
    private final DeliveryRetryRegistrar retryRegistrar;
    private final Clock clock;
    private final DeliveryMetrics metrics;

    @Transactional
    public void retry(NotificationId notificationId) {
        List<Delivery> dead = deliveryRepository
            .findAllByNotificationIdAndStateForUpdate(notificationId.value(), DeliveryState.DEAD);
        if (dead.isEmpty()) {
            throw new NoRetriableDeliveryException("no DEAD delivery for " + notificationId.value());
        }
        Instant now = clock.instant();
        for (Delivery d : dead) {
            d.markPending(now);   // DEAD → PENDING, attempt_count preserved
            retryRegistrar.issueNewAttempt(d.getId());   // new delivery_attempt READY row
        }
        metrics.recordAdminRetry();
    }
}
