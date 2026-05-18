package com.livenotification.delivery.domain;

import java.util.UUID;

public record DeliveryId(UUID value) {
    public DeliveryId {
        if (value == null)
            throw new IllegalArgumentException("DeliveryId must not be null");
    }

    public static DeliveryId of(UUID value) {
        return new DeliveryId(value);
    }
}
