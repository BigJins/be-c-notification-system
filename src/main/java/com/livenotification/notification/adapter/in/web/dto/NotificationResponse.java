package com.livenotification.notification.adapter.in.web.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.livenotification.notification.application.NotificationDetail;
import com.livenotification.notification.domain.NotificationType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record NotificationResponse(
        UUID id, String eventId, String recipientId,
        NotificationType type, JsonNode payload,
        Instant readAt, Instant createdAt, Instant updatedAt,
        List<DeliveryResponse> deliveries) {

    public static NotificationResponse from(NotificationDetail detail) {
        var n = detail.notification();
        return new NotificationResponse(
            n.getId().value(),
            n.getEventId().value(),
            n.getRecipientId().value(),
            n.getType(),
            n.payload().value(),
            n.getReadAt(),
            n.getCreatedAt(),
            n.getUpdatedAt(),
            detail.deliveries().stream().map(DeliveryResponse::from).toList());
    }
}
