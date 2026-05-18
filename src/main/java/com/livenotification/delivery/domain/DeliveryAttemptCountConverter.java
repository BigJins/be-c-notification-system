package com.livenotification.delivery.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class DeliveryAttemptCountConverter implements AttributeConverter<DeliveryAttemptCount, Integer> {
    @Override
    public Integer convertToDatabaseColumn(DeliveryAttemptCount attr) {
        return attr == null ? null : attr.value();
    }

    @Override
    public DeliveryAttemptCount convertToEntityAttribute(Integer db) {
        return db == null ? null : new DeliveryAttemptCount(db);
    }
}
