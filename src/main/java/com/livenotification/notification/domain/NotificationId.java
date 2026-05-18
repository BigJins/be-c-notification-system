package com.livenotification.notification.domain;

import java.util.UUID;

public record NotificationId(UUID value) {
    public NotificationId {
        if (value == null)
            throw new IllegalArgumentException("NotificationId must not be null");
    }

    public static NotificationId of(UUID value) {
        return new NotificationId(value);
    }
}
