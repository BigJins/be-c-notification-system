package com.livenotification.notification.application.port;

import com.livenotification.delivery.domain.ChannelType;
import com.livenotification.notification.domain.NotificationId;

import java.util.List;

public interface DeliveryRegistrar {
    void scheduleFor(NotificationId notificationId, List<ChannelType> channels);
}
