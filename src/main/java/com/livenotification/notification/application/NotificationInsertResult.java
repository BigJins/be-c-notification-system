package com.livenotification.notification.application;

import com.livenotification.notification.domain.Notification;

public record NotificationInsertResult(Notification notification, boolean inserted) {
}
