package com.livenotification.delivery.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.UUID;

@Converter(autoApply = true)
public class DeliveryAttemptIdConverter implements AttributeConverter<DeliveryAttemptId, UUID> {
    @Override
    public UUID convertToDatabaseColumn(DeliveryAttemptId attr) {
        return attr == null ? null : attr.value();
    }

    @Override
    public DeliveryAttemptId convertToEntityAttribute(UUID db) {
        return db == null ? null : new DeliveryAttemptId(db);
    }
}
