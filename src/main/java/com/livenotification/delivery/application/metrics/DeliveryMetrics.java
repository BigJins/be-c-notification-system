package com.livenotification.delivery.application.metrics;

import com.livenotification.delivery.domain.ChannelType;
import com.livenotification.notification.domain.NotificationType;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DeliveryMetrics {

    private final MeterRegistry registry;

    /** #1 notification.registered, tag: type */
    public void recordRegistered(NotificationType type) {
        registry.counter("notification.registered", "type", type.name()).increment();
    }

    /** #2 delivery.dispatched, tag: channel/type/result (SENT|FAILED_TRANSIENT|FAILED_TRANSIENT_DEAD|FAILED_PERMANENT|DEAD) */
    public void recordDispatched(ChannelType channel, NotificationType type, String result) {
        registry.counter("delivery.dispatched",
            "channel", channel.name(),
            "type", type.name(),
            "result", result).increment();
    }

    /** #3 delivery.dispatch.duration timer, tag: channel/type */
    public Timer dispatchTimer(ChannelType channel, NotificationType type) {
        return registry.timer("delivery.dispatch.duration",
            "channel", channel.name(),
            "type", type.name());
    }

    /** #6 notification.idempotency.replay */
    public void recordIdempotencyReplay() {
        registry.counter("notification.idempotency.replay").increment();
    }

    /** #8 delivery.dead, tag: channel/type */
    public void recordDead(ChannelType channel, NotificationType type) {
        registry.counter("delivery.dead",
            "channel", channel.name(),
            "type", type.name()).increment();
    }

    /** #9 delivery.admin.retried */
    public void recordAdminRetry() {
        registry.counter("delivery.admin.retried").increment();
    }

    /** #10 delivery_attempt.cleanup.deleted (increment by N) */
    public void recordCleanupDeleted(long count) {
        registry.counter("delivery_attempt.cleanup.deleted").increment(count);
    }

    /** #11 notification.design.violation, tag: kind. 0=normal, >0=alert. */
    public void recordDesignViolation(String kind) {
        registry.counter("notification.design.violation", "kind", kind).increment();
    }
}
