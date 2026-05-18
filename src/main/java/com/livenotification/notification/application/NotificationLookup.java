package com.livenotification.notification.application;

import com.livenotification.notification.domain.NotificationId;

import java.util.Optional;

public interface NotificationLookup {
    Optional<NotificationView> findById(NotificationId id);
}
