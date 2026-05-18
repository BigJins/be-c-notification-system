package com.livenotification.notification.adapter.in.web.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.livenotification.delivery.domain.ChannelType;
import com.livenotification.notification.application.RegisterCommand;
import com.livenotification.notification.domain.EventId;
import com.livenotification.notification.domain.NotificationPayload;
import com.livenotification.notification.domain.NotificationType;
import com.livenotification.notification.domain.RecipientId;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record RegisterNotificationRequest(
        @NotBlank @Size(max = 64) String eventId,
        @NotBlank @Size(max = 64) String recipientId,
        @NotNull NotificationType type,
        @NotEmpty List<ChannelType> channels,
        @NotNull JsonNode payload) {

    /** channels distinct normalize: same channel twice → 1 (API-level dedupe). */
    public RegisterCommand toCommand() {
        return new RegisterCommand(
            new EventId(eventId), new RecipientId(recipientId), type,
            channels.stream().distinct().toList(),
            new NotificationPayload(payload));
    }
}
