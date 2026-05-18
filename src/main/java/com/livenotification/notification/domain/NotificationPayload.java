package com.livenotification.notification.domain;

import com.fasterxml.jackson.databind.JsonNode;

public record NotificationPayload(JsonNode value) {
    public NotificationPayload {
        java.util.Objects.requireNonNull(value, "payload must not be null");
    }

    public static NotificationPayload of(JsonNode value) {
        return new NotificationPayload(value);
    }
}
