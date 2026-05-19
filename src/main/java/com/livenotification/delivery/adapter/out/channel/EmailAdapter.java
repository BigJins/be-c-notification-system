package com.livenotification.delivery.adapter.out.channel;

import com.livenotification.delivery.application.port.ChannelAdapter;
import com.livenotification.delivery.domain.ChannelType;
import com.livenotification.delivery.domain.Delivery;
import com.livenotification.delivery.domain.DispatchResult;
import com.livenotification.notification.application.NotificationView;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Mock EMAIL adapter. Honors {@link ChannelAdapter} contract — Success or DispatchResult.Failure,
 * never throws. When this is replaced with a real SMTP/SES client (see README §8 미구현 표 #4),
 * the new implementation must catch every transport exception here and map per design §5:
 * 5xx/timeouts/IO → TransientFailure, 4xx/invalid recipient/payload too large → PermanentFailure.
 * Per ADR-0001, classification stays in the adapter (no central FailureClassifier).
 */
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
