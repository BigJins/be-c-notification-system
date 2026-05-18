package com.livenotification.delivery.domain;

public record DeliveryAttemptSessionCount(int value) {
    public DeliveryAttemptSessionCount {
        if (value < 0)
            throw new IllegalArgumentException("attempt session count must be >= 0");
    }

    public static DeliveryAttemptSessionCount zero() {
        return new DeliveryAttemptSessionCount(0);
    }

    public DeliveryAttemptSessionCount increment() {
        return new DeliveryAttemptSessionCount(value + 1);
    }
}
