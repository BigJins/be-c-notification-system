package com.livenotification.notification.application;

import com.livenotification.delivery.domain.ChannelType;
import com.livenotification.notification.domain.EventId;
import com.livenotification.notification.domain.NotificationPayload;
import com.livenotification.notification.domain.NotificationType;
import com.livenotification.notification.domain.RecipientId;

import java.util.List;

public record RegisterCommand(EventId eventId, RecipientId recipientId, NotificationType type,
                                List<ChannelType> channels, NotificationPayload payload) {}
