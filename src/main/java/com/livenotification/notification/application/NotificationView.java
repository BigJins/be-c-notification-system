package com.livenotification.notification.application;

import com.livenotification.notification.domain.*;

import java.time.Instant;

public record NotificationView(NotificationId id, EventId eventId, RecipientId recipientId,
                                NotificationType type, NotificationPayload payload, Instant readAt) {

    public static NotificationView from(Notification n) {
        return new NotificationView(n.getId(), n.getEventId(), n.getRecipientId(),
            n.getType(), n.payload(), n.getReadAt());
    }
}
