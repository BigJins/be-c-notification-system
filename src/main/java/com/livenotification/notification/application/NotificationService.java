package com.livenotification.notification.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.livenotification.delivery.application.DeliveryRepository;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
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
    private final IdempotencyService idempotencyService;
    private final NotificationProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Transactional
    public RegisterResult register(RegisterCommand cmd, IdempotencyKey headerKey) {
        // 1. Idempotency-Key dedup (2차) — only if header present
        RequestHash hash = null;
        if (headerKey != null) {
            hash = RequestHash.of(cmd, objectMapper);
            var result = idempotencyService.lookupCurrent(headerKey, hash);
            switch (result) {
                case IdempotencyResult.HitSameHash hit -> {
                    return loadReplay(new NotificationId(hit.targetId()));
                }
                case IdempotencyResult.HitDifferentHash hdh ->
                    throw new IdempotencyConflictException(
                        "Idempotency-Key reused with different body");
                case IdempotencyResult.Miss miss -> { /* fall through */ }
            }
        }

        // 2. Event-level dedup (1차) — DB unique constraint
        boolean eventDuplicate = false;
        Notification notification;
        try {
            notification = Notification.create(
                cmd.eventId(), cmd.recipientId(), cmd.type(), cmd.payload(), clock);
            notificationRepository.save(notification);   // may throw DataIntegrityViolation
            notificationRepository.flush();              // force constraint check NOW
        } catch (DataIntegrityViolationException dive) {
            // 1차 dedup hit — fetch existing
            eventDuplicate = true;
            notification = notificationRepository
                .findByEventIdAndRecipientIdAndType(cmd.eventId(), cmd.recipientId(), cmd.type())
                .orElseThrow(() -> new IllegalStateException(
                    "1차 dedup conflict but existing row not found", dive));
            // Since notification existed, deliveries already exist — skip scheduleFor
            // Persist idempotency record if header present (links header to EXISTING notification)
            if (headerKey != null && hash != null) {
                try {
                    idempotencyService.persist(headerKey, hash,
                        notification.getId().value(),
                        properties.cleanup().idempotencyRetention());
                } catch (DataIntegrityViolationException race) {
                    // Concurrent same-key persist — re-lookup will return HitSameHash for next caller
                }
            }
            List<Delivery> existingDeliveries = deliveryRepository.findAllByNotificationId(notification.getId());
            return new RegisterResult(
                new NotificationDetail(notification, existingDeliveries),
                true,   // eventDuplicate
                false); // replay (header dedup did not hit)
        }

        // 3. Fresh notification — schedule deliveries (cascade INSERT)
        deliveryRegistrar.scheduleFor(notification.getId(), cmd.channels());

        // 4. Persist idempotency record (links header to NEW notification)
        if (headerKey != null && hash != null) {
            try {
                idempotencyService.persist(headerKey, hash,
                    notification.getId().value(),
                    properties.cleanup().idempotencyRetention());
            } catch (DataIntegrityViolationException race) {
                // Concurrent same-key persist — re-lookup would return HitSameHash; we already committed.
            }
        }

        // 5. Assemble response
        List<Delivery> deliveries = deliveryRepository.findAllByNotificationId(notification.getId());
        return new RegisterResult(
            new NotificationDetail(notification, deliveries),
            false,   // eventDuplicate
            false);  // replay
    }

    private RegisterResult loadReplay(NotificationId id) {
        Notification n = notificationRepository.findById(id)
            .orElseThrow(() -> new IllegalStateException("idempotency target missing: " + id));
        List<Delivery> deliveries = deliveryRepository.findAllByNotificationId(id);
        return new RegisterResult(new NotificationDetail(n, deliveries), false, true);
    }

    @Transactional
    public void markRead(NotificationId id) {
        Notification n = notificationRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("notification not found: " + id));
        // Invariant 2 (CLAUDE.md): read_at NOT NULL allowed only if IN_APP delivery is SENT
        boolean inAppSent = deliveryRepository.existsByNotificationIdAndChannelAndState(
            id, ChannelType.IN_APP, DeliveryState.SENT);
        if (!inAppSent) {
            throw new ReadStateViolationException(
                "cannot mark read: no IN_APP delivery in SENT state for " + id);
        }
        n.markRead(clock.instant());
    }

    public NotificationDetail loadDetail(NotificationId id) {
        Notification n = notificationRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("notification not found: " + id));
        List<Delivery> deliveries = deliveryRepository.findAllByNotificationId(id);
        return new NotificationDetail(n, deliveries);
    }

    public Page<NotificationDetail> findByRecipient(RecipientId recipientId, Boolean read, Pageable pageable) {
        // Step 1: page Notifications by recipientId (read filter applied below)
        List<Notification> all = notificationRepository.findAllByRecipientId(recipientId, pageable);
        long total = notificationRepository.countByRecipientId(recipientId);

        // Step 2: apply read filter (in-memory; SQL-level filter would require new repo method)
        List<Notification> filtered;
        if (read == null) {
            filtered = all;
        } else if (read) {
            filtered = all.stream().filter(n -> n.getReadAt() != null).toList();
        } else {
            filtered = all.stream().filter(n -> n.getReadAt() == null).toList();
        }

        if (filtered.isEmpty()) {
            return new PageImpl<>(List.of(), pageable, total);
        }

        // Step 3: IN-clause batch fetch deliveries to avoid N+1
        List<NotificationId> ids = filtered.stream().map(Notification::getId).toList();
        Map<NotificationId, List<Delivery>> byNotification = deliveryRepository
            .findAllByNotificationIdIn(ids).stream()
            .collect(Collectors.groupingBy(Delivery::getNotificationId));

        List<NotificationDetail> details = filtered.stream()
            .map(n -> new NotificationDetail(n, byNotification.getOrDefault(n.getId(), List.of())))
            .toList();

        return new PageImpl<>(details, pageable, total);
    }
}
