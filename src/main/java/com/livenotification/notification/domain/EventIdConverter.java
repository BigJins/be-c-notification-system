package com.livenotification.notification.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class EventIdConverter implements AttributeConverter<EventId, String> {
    @Override
    public String convertToDatabaseColumn(EventId attr) {
        return attr == null ? null : attr.value();
    }

    @Override
    public EventId convertToEntityAttribute(String db) {
        return db == null ? null : new EventId(db);
    }
}
