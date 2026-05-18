package com.livenotification.notification.adapter.in.web.dto;

import com.livenotification.delivery.domain.ChannelType;
import com.livenotification.delivery.domain.Delivery;
import com.livenotification.delivery.domain.DeliveryState;

import java.time.Instant;
import java.util.UUID;

public record DeliveryResponse(
        UUID id, ChannelType channel, DeliveryState state,
        int attemptCount, String lastError, Instant sentAt,
        Instant createdAt, Instant updatedAt) {

    public static DeliveryResponse from(Delivery d) {
        return new DeliveryResponse(
            d.getId().value(),
            d.getChannel(),
            d.getState(),
            d.getAttemptCount().value(),
            d.getLastError(),
            d.getSentAt(),
            d.getCreatedAt(),
            d.getUpdatedAt());
    }
}
