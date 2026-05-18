package com.livenotification.delivery.domain;

public record DeliveryAttemptCount(int value) {
    public DeliveryAttemptCount {
        if (value < 0)
            throw new IllegalArgumentException("attempt count must be >= 0");
    }

    public static DeliveryAttemptCount zero() {
        return new DeliveryAttemptCount(0);
    }

    public DeliveryAttemptCount increment() {
        return new DeliveryAttemptCount(value + 1);
    }
}
