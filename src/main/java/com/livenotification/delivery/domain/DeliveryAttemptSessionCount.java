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

    public DeliveryAttemptSessionCount incrementUntil(int maxAttempts) {
        DeliveryAttemptSessionCount next = increment();
        return next.requireAtMost(maxAttempts);
    }

    public DeliveryAttemptSessionCount requireAtMost(int maxAttempts) {
        if (maxAttempts < 0)
            throw new IllegalArgumentException("maxAttempts must be >= 0");
        if (value > maxAttempts)
            throw new IllegalArgumentException("attempt session count must be <= maxAttempts");
        return this;
    }
}
