package com.livenotification.delivery.domain;

import java.util.UUID;

public record DeliveryAttemptId(UUID value) {
    public DeliveryAttemptId {
        if (value == null)
            throw new IllegalArgumentException("DeliveryAttemptId must not be null");
    }

    public static DeliveryAttemptId of(UUID value) {
        return new DeliveryAttemptId(value);
    }
}
