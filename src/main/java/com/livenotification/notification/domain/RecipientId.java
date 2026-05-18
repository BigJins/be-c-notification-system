package com.livenotification.notification.domain;

public record RecipientId(String value) {
    public RecipientId {
        if (value == null || value.isBlank())
            throw new IllegalArgumentException("recipientId must not be blank");
        if (value.length() > 64)
            throw new IllegalArgumentException("recipientId length must be <= 64");
    }

    public static RecipientId of(String value) {
        return new RecipientId(value);
    }
}
