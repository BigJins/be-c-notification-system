package com.livenotification.idempotency.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class RequestHashConverter implements AttributeConverter<RequestHash, String> {
    @Override
    public String convertToDatabaseColumn(RequestHash attr) {
        return attr == null ? null : attr.value();
    }

    @Override
    public RequestHash convertToEntityAttribute(String db) {
        return db == null ? null : new RequestHash(db);
    }
}
