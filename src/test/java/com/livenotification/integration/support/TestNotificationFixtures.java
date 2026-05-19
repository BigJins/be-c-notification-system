package com.livenotification.integration.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.livenotification.delivery.domain.ChannelType;
import com.livenotification.notification.domain.NotificationType;

import java.util.List;
import java.util.Map;

public final class TestNotificationFixtures {
    private TestNotificationFixtures() {}

    public static ObjectNode payloadNormal(ObjectMapper m) {
        return m.createObjectNode()
            .put("subject", "Order confirmed")
            .put("body", "Thanks for your order");
    }

    public static ObjectNode payloadWithTransientFailure(ObjectMapper m) {
        return m.createObjectNode()
            .put("subject", "transient-test")
            .put("x_test_failure", "transient");
    }

    public static ObjectNode payloadWithPermanentFailure(ObjectMapper m) {
        return m.createObjectNode()
            .put("subject", "permanent-test")
            .put("x_test_failure", "permanent");
    }

    public static Map<String, Object> registerBody(String eventId, String recipientId,
                                                    NotificationType type,
                                                    List<ChannelType> channels,
                                                    JsonNode payload) {
        return Map.of(
            "eventId", eventId,
            "recipientId", recipientId,
            "type", type.name(),
            "channels", channels.stream().map(Enum::name).toList(),
            "payload", payload);
    }
}
