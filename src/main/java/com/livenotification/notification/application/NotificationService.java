package com.livenotification.notification.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.livenotification.delivery.application.DeliveryRepository;
import com.livenotification.delivery.application.metrics.DeliveryMetrics;
import com.livenotification.delivery.domain.ChannelType;
import com.livenotification.delivery.domain.Delivery;
import com.livenotification.delivery.domain.DeliveryState;
import com.livenotification.global.config.NotificationProperties;
import com.livenotification.idempotency.application.IdempotencyResult;
import com.livenotification.idempotency.application.IdempotencyService;
import com.livenotification.idempotency.domain.IdempotencyKey;
import com.livenotification.idempotency.domain.RequestHash;
import com.livenotification.notification.application.exception.IdempotencyConflictException;
import com.livenotification.notification.application.exception.ReadStateViolationException;
import com.livenotification.notification.application.port.DeliveryRegistrar;
import com.livenotification.notification.domain.Notification;
import com.livenotification.notification.domain.NotificationId;
import com.livenotification.notification.domain.RecipientId;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final DeliveryRepository deliveryRepository;
    private final DeliveryRegistrar deliveryRegistrar;
    private final NotificationRegistrationStore notificationRegistrationStore;
    private final IdempotencyService idempotencyService;
    private final NotificationProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final DeliveryMetrics metrics;

    /**
     * Three-phase registration. See {@link RegisterOutcome} for the result shape and
     * {@code IdempotencyService} class javadoc for the gate/bind contract.
     *
     * <pre>
     *   phase 1 — gate:   idempotencyService.checkOutcome(K, H)
     *                       HitSameHash → return IdempotentReplay (early)
     *                       HitDifferentHash → throw 409
     *                       Miss → fall through
     *   phase 2 — work:   notificationRegistrationStore.insertOrLoad(cmd)
     *                     if newly inserted: deliveryRegistrar.scheduleFor(...)
     *   phase 3 — bind:   idempotencyService.bind(K, H, notification.id, ttl)   [if K present]
     * </pre>
     *
     * The bind call is hoisted above the inserted/!inserted branch — both paths bind
     * identically. Phase 1 and phase 3 cannot be folded because phase 2 produces the
     * {@code targetId} that phase 3 needs.
     */
    @Transactional
    public RegisterOutcome register(RegisterCommand cmd, IdempotencyKey headerKey) {
        RequestHash hash = null;
        if (headerKey != null) {
            hash = RequestHash.of(cmd, objectMapper);
            IdempotencyResult gate = idempotencyService.checkOutcome(headerKey, hash);
            switch (gate) {
                case IdempotencyResult.HitSameHash hit -> {
                    metrics.recordIdempotencyReplay();
                    return new RegisterOutcome.IdempotentReplay(loadDetail(new NotificationId(hit.targetId())));
                }
                case IdempotencyResult.HitDifferentHash ignored ->
                    throw new IdempotencyConflictException("Idempotency-Key reused with different body");
                case IdempotencyResult.Miss ignored -> {
                }
            }
        }

        NotificationInsertResult insertResult = notificationRegistrationStore.insertOrLoad(cmd);
        Notification notification = insertResult.notification();

        if (insertResult.inserted()) {
            metrics.recordRegistered(cmd.type());
            deliveryRegistrar.scheduleFor(notification.getId(), cmd.channels());
        }

        if (headerKey != null) {
            idempotencyService.bind(
                headerKey,
                hash,
                notification.getId().value(),
                properties.cleanup().idempotencyRetention());
        }

        NotificationDetail detail = new NotificationDetail(
            notification,
            deliveryRepository.findAllByNotificationId(notification.getId()));

        return insertResult.inserted()
            ? new RegisterOutcome.NewlyCreated(detail)
            : new RegisterOutcome.EventDuplicate(detail);
    }

    @Transactional
    public void markRead(NotificationId id) {
        Notification notification = notificationRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("notification not found: " + id));
        boolean inAppSent = deliveryRepository.existsByNotificationIdAndChannelAndState(
            id, ChannelType.IN_APP, DeliveryState.SENT);
        if (!inAppSent) {
            throw new ReadStateViolationException(
                "cannot mark read: no IN_APP delivery in SENT state for " + id);
        }
        notification.markRead(clock.instant());
    }

    public NotificationDetail loadDetail(NotificationId id) {
        Notification notification = notificationRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("notification not found: " + id));
        List<Delivery> deliveries = deliveryRepository.findAllByNotificationId(id);
        return new NotificationDetail(notification, deliveries);
    }

    public Page<NotificationDetail> findByRecipient(RecipientId recipientId, Boolean read, Pageable pageable) {
        Page<Notification> page;
        if (read == null) {
            page = notificationRepository.findAllByRecipientIdOrderByCreatedAtDesc(recipientId.value(), pageable);
        } else if (read) {
            page = notificationRepository.findAllByRecipientIdAndReadAtIsNotNullOrderByCreatedAtDesc(
                recipientId.value(), pageable);
        } else {
            page = notificationRepository.findAllByRecipientIdAndReadAtIsNullOrderByCreatedAtDesc(
                recipientId.value(), pageable);
        }

        if (page.isEmpty()) {
            return page.map(notification -> new NotificationDetail(notification, List.of()));
        }

        List<NotificationId> ids = page.getContent().stream().map(Notification::getId).toList();
        Map<NotificationId, List<Delivery>> byNotification = deliveryRepository
            .findAllByNotificationIdInNotificationIds(ids).stream()
            .collect(Collectors.groupingBy(Delivery::getNotificationId));

        return page.map(notification -> new NotificationDetail(
            notification,
            byNotification.getOrDefault(notification.getId(), List.of())));
    }
}
