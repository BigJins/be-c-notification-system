package com.livenotification.notification.application;

import com.livenotification.notification.domain.EventId;
import com.livenotification.notification.domain.Notification;
import com.livenotification.notification.domain.NotificationId;
import com.livenotification.notification.domain.NotificationType;
import com.livenotification.notification.domain.RecipientId;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface NotificationRepository extends JpaRepository<Notification, NotificationId> {

    Optional<Notification> findByEventIdAndRecipientIdAndType(EventId eventId,
                                                              RecipientId recipientId,
                                                              NotificationType type);

    List<Notification> findAllByRecipientId(RecipientId recipientId, Pageable pageable);

    long countByRecipientId(RecipientId recipientId);
}
