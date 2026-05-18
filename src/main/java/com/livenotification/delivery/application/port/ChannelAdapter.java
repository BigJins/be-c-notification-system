package com.livenotification.delivery.application.port;

import com.livenotification.delivery.domain.Delivery;
import com.livenotification.delivery.domain.ChannelType;
import com.livenotification.delivery.domain.DispatchResult;
import com.livenotification.notification.application.NotificationView;

public interface ChannelAdapter {
    ChannelType type();
    DispatchResult send(NotificationView notification, Delivery delivery);
}
