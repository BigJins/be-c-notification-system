package com.livenotification.delivery.application;

import com.livenotification.delivery.domain.ChannelType;
import com.livenotification.delivery.domain.Delivery;
import com.livenotification.delivery.domain.DeliveryState;
import com.livenotification.notification.domain.NotificationId;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface DeliveryRepository extends JpaRepository<Delivery, UUID> {

    List<Delivery> findAllByNotificationId(UUID notificationId);

    List<Delivery> findAllByNotificationIdIn(Collection<UUID> notificationIds);

    boolean existsByNotificationIdAndChannelAndState(UUID notificationId,
                                                     ChannelType channel,
                                                     DeliveryState state);

    List<Delivery> findAllByNotificationIdAndState(UUID notificationId,
                                                   DeliveryState state);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from Delivery d where d.notificationId = :notificationId and d.state = :state")
    List<Delivery> findAllByNotificationIdAndStateForUpdate(@Param("notificationId") UUID notificationId,
                                                            @Param("state") DeliveryState state);

    default List<Delivery> findAllByNotificationId(NotificationId notificationId) {
        return findAllByNotificationId(notificationId.value());
    }

    default List<Delivery> findAllByNotificationIdInNotificationIds(Collection<NotificationId> notificationIds) {
        return findAllByNotificationIdIn(notificationIds.stream().map(NotificationId::value).toList());
    }

    default boolean existsByNotificationIdAndChannelAndState(NotificationId notificationId,
                                                             ChannelType channel,
                                                             DeliveryState state) {
        return existsByNotificationIdAndChannelAndState(notificationId.value(), channel, state);
    }
}
