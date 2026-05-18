package com.livenotification.delivery.application;

import com.livenotification.delivery.domain.ChannelType;
import com.livenotification.delivery.domain.Delivery;
import com.livenotification.delivery.domain.DeliveryId;
import com.livenotification.delivery.domain.DeliveryState;
import com.livenotification.notification.domain.NotificationId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface DeliveryRepository extends JpaRepository<Delivery, DeliveryId> {

    List<Delivery> findAllByNotificationId(NotificationId notificationId);

    List<Delivery> findAllByNotificationIdIn(Collection<NotificationId> notificationIds);

    boolean existsByNotificationIdAndChannelAndState(NotificationId notificationId,
                                                     ChannelType channel,
                                                     DeliveryState state);

    List<Delivery> findAllByNotificationIdAndState(NotificationId notificationId,
                                                   DeliveryState state);
}
