package com.livenotification.notification.application;

import com.livenotification.notification.domain.EventId;
import com.livenotification.notification.domain.Notification;
import com.livenotification.notification.domain.NotificationId;
import com.livenotification.notification.domain.NotificationType;
import com.livenotification.notification.domain.RecipientId;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    Optional<Notification> findByEventIdAndRecipientIdAndType(String eventId,
                                                              String recipientId,
                                                              NotificationType type);

    Page<Notification> findAllByRecipientIdOrderByCreatedAtDesc(String recipientId, Pageable pageable);

    Page<Notification> findAllByRecipientIdAndReadAtIsNullOrderByCreatedAtDesc(String recipientId, Pageable pageable);

    Page<Notification> findAllByRecipientIdAndReadAtIsNotNullOrderByCreatedAtDesc(String recipientId, Pageable pageable);

    default Optional<Notification> findById(NotificationId id) {
        return findById(id.value());
    }

    default Optional<Notification> findByEventIdAndRecipientIdAndType(EventId eventId,
                                                                      RecipientId recipientId,
                                                                      NotificationType type) {
        return findByEventIdAndRecipientIdAndType(eventId.value(), recipientId.value(), type);
    }
}
