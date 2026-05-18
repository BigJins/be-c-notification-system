package com.livenotification.delivery.adapter.in.registrar;

import com.livenotification.delivery.application.DeliveryAttemptRepository;
import com.livenotification.delivery.application.DeliveryRepository;
import com.livenotification.delivery.domain.ChannelType;
import com.livenotification.delivery.domain.Delivery;
import com.livenotification.delivery.domain.DeliveryAttempt;
import com.livenotification.notification.application.port.DeliveryRegistrar;
import com.livenotification.notification.domain.NotificationId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;

@Component
@RequiredArgsConstructor
public class DeliveryRegistrarAdapter implements DeliveryRegistrar {

    private final DeliveryRepository deliveryRepository;
    private final DeliveryAttemptRepository deliveryAttemptRepository;
    private final Clock clock;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void scheduleFor(NotificationId notificationId, List<ChannelType> channels) {
        for (ChannelType channel : channels) {
            Delivery delivery = (channel == ChannelType.IN_APP)
                ? Delivery.forInApp(notificationId, clock)
                : Delivery.forEmail(notificationId, clock);
            deliveryRepository.save(delivery);

            DeliveryAttempt attempt = (channel == ChannelType.IN_APP)
                ? DeliveryAttempt.completedFor(delivery.getId(), clock)
                : DeliveryAttempt.readyFor(delivery.getId(), clock);
            deliveryAttemptRepository.save(attempt);
        }
    }
}
