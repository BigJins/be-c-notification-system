package com.livenotification.delivery.adapter.out.channel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.livenotification.delivery.domain.ChannelType;
import com.livenotification.delivery.domain.Delivery;
import com.livenotification.delivery.domain.DispatchResult;
import com.livenotification.notification.application.NotificationView;
import com.livenotification.notification.domain.*;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class EmailAdapterTest {

    private final EmailAdapter adapter = new EmailAdapter();
    private final ObjectMapper mapper = new ObjectMapper();
    private final Clock clock = Clock.fixed(Instant.parse("2026-05-18T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void send_normalPayload_returnsSuccess() {
        NotificationView view = viewWithPayload(mapper.createObjectNode().put("subject", "test"));
        Delivery delivery = Delivery.forEmail(view.id(), clock);

        DispatchResult result = adapter.send(view, delivery);

        assertThat(result).isInstanceOf(DispatchResult.Success.class);
    }

    @Test
    void send_transientFailureInjection_returnsTransientFailure() {
        ObjectNode payload = mapper.createObjectNode()
            .put("subject", "test")
            .put("x_test_failure", "transient");
        NotificationView view = viewWithPayload(payload);
        Delivery delivery = Delivery.forEmail(view.id(), clock);

        DispatchResult result = adapter.send(view, delivery);

        assertThat(result).isInstanceOf(DispatchResult.TransientFailure.class);
    }

    @Test
    void send_permanentFailureInjection_returnsPermanentFailure() {
        ObjectNode payload = mapper.createObjectNode()
            .put("subject", "test")
            .put("x_test_failure", "permanent");
        NotificationView view = viewWithPayload(payload);
        Delivery delivery = Delivery.forEmail(view.id(), clock);

        DispatchResult result = adapter.send(view, delivery);

        assertThat(result).isInstanceOf(DispatchResult.PermanentFailure.class);
    }

    @Test
    void type_returnsEmail() {
        assertThat(adapter.type()).isEqualTo(ChannelType.EMAIL);
    }

    private NotificationView viewWithPayload(com.fasterxml.jackson.databind.JsonNode payload) {
        return new NotificationView(
            new NotificationId(UUID.randomUUID()),
            new EventId("e1"),
            new RecipientId("u1"),
            NotificationType.PAYMENT_CONFIRMED,
            new NotificationPayload(payload),
            null);
    }
}
