package com.livenotification.delivery.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class DeliveryAttemptSessionCountConverter implements AttributeConverter<DeliveryAttemptSessionCount, Integer> {
    @Override
    public Integer convertToDatabaseColumn(DeliveryAttemptSessionCount attr) {
        return attr == null ? null : attr.value();
    }

    @Override
    public DeliveryAttemptSessionCount convertToEntityAttribute(Integer db) {
        return db == null ? null : new DeliveryAttemptSessionCount(db);
    }
}
