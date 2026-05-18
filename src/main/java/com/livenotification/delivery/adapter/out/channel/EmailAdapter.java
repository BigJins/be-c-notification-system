package com.livenotification.delivery.adapter.out.channel;

import com.livenotification.delivery.application.port.ChannelAdapter;
import com.livenotification.delivery.domain.ChannelType;
import com.livenotification.delivery.domain.Delivery;
import com.livenotification.delivery.domain.DispatchResult;
import com.livenotification.notification.application.NotificationView;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class EmailAdapter implements ChannelAdapter {

    @Override
    public ChannelType type() {
        return ChannelType.EMAIL;
    }

    @Override
    public DispatchResult send(NotificationView notification, Delivery delivery) {
        String injection = notification.payload().value().path("x_test_failure").asText("");
        if ("transient".equals(injection)) {
            return new DispatchResult.TransientFailure(
                "test transient",
                new RuntimeException("x_test_failure=transient"));
        }
        if ("permanent".equals(injection)) {
            return new DispatchResult.PermanentFailure(
                "test permanent",
                new IllegalArgumentException("x_test_failure=permanent"));
        }
        log.info("EMAIL sent (mock) notificationId={} recipient={} type={}",
            notification.id().value(),
            notification.recipientId().value(),
            notification.type());
        return new DispatchResult.Success();
    }
}
