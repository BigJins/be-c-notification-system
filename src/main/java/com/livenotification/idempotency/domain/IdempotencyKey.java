package com.livenotification.idempotency.domain;

public record IdempotencyKey(String value) {
    public IdempotencyKey {
        if (value == null || value.isBlank())
            throw new IllegalArgumentException("idempotencyKey must not be blank");
        if (value.length() > 128)
            throw new IllegalArgumentException("idempotencyKey length must be <= 128");
    }

    public static IdempotencyKey of(String value) {
        return new IdempotencyKey(value);
    }
}
