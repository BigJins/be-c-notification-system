package com.livenotification.notification.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class RecipientIdConverter implements AttributeConverter<RecipientId, String> {
    @Override
    public String convertToDatabaseColumn(RecipientId attr) {
        return attr == null ? null : attr.value();
    }

    @Override
    public RecipientId convertToEntityAttribute(String db) {
        return db == null ? null : new RecipientId(db);
    }
}
