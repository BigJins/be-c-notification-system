package com.livenotification.notification.domain;

public record EventId(String value) {
    public EventId {
        if (value == null || value.isBlank())
            throw new IllegalArgumentException("eventId must not be blank");
        if (value.length() > 64)
            throw new IllegalArgumentException("eventId length must be <= 64");
    }

    public static EventId of(String value) {
        return new EventId(value);
    }
}
