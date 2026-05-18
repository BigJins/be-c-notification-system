package com.livenotification.delivery.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.UUID;

@Converter(autoApply = true)
public class DeliveryIdConverter implements AttributeConverter<DeliveryId, UUID> {
    @Override
    public UUID convertToDatabaseColumn(DeliveryId attr) {
        return attr == null ? null : attr.value();
    }

    @Override
    public DeliveryId convertToEntityAttribute(UUID db) {
        return db == null ? null : new DeliveryId(db);
    }
}
