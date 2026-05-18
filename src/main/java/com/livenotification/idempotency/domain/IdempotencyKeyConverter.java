package com.livenotification.idempotency.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class IdempotencyKeyConverter implements AttributeConverter<IdempotencyKey, String> {
    @Override
    public String convertToDatabaseColumn(IdempotencyKey attr) {
        return attr == null ? null : attr.value();
    }

    @Override
    public IdempotencyKey convertToEntityAttribute(String db) {
        return db == null ? null : new IdempotencyKey(db);
    }
}
