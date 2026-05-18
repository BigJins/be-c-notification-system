package com.livenotification.delivery.adapter.out.channel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.livenotification.delivery.application.metrics.DeliveryMetrics;
import com.livenotification.delivery.domain.ChannelType;
import com.livenotification.delivery.domain.Delivery;
import com.livenotification.delivery.domain.DispatchResult;
import com.livenotification.notification.application.NotificationView;
import com.livenotification.notification.domain.EventId;
import com.livenotification.notification.domain.NotificationId;
import com.livenotification.notification.domain.NotificationPayload;
import com.livenotification.notification.domain.NotificationType;
import com.livenotification.notification.domain.RecipientId;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class InAppAdapterTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final DeliveryMetrics metrics = new DeliveryMetrics(registry);
    private final InAppAdapter adapter = new InAppAdapter(metrics);
    private final ObjectMapper mapper = new ObjectMapper();
    private final Clock clock = Clock.fixed(Instant.parse("2026-05-18T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void send_alwaysReturnsPermanentFailureAndIncrementsCounter() {
        NotificationView view = new NotificationView(
            new NotificationId(UUID.randomUUID()),
            new EventId("e1"),
            new RecipientId("u1"),
            NotificationType.PAYMENT_CONFIRMED,
            new NotificationPayload(mapper.createObjectNode()),
            null);
        Delivery delivery = Delivery.forInApp(view.id(), clock);

        DispatchResult result = adapter.send(view, delivery);

        assertThat(result).isInstanceOf(DispatchResult.PermanentFailure.class);
        assertThat(registry.counter("notification.design.violation",
            "kind", "inapp_dispatch_attempted").count()).isEqualTo(1.0);
    }

    @Test
    void type_returnsInApp() {
        assertThat(adapter.type()).isEqualTo(ChannelType.IN_APP);
    }
}
