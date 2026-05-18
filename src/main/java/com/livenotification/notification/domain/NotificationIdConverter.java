package com.livenotification.notification.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.UUID;

@Converter(autoApply = true)
public class NotificationIdConverter implements AttributeConverter<NotificationId, UUID> {
    @Override
    public UUID convertToDatabaseColumn(NotificationId attr) {
        return attr == null ? null : attr.value();
    }

    @Override
    public NotificationId convertToEntityAttribute(UUID db) {
        return db == null ? null : new NotificationId(db);
    }
}
