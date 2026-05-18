package com.livenotification.delivery.application.metrics;

import com.livenotification.delivery.application.DeliveryAttemptRepository;
import com.livenotification.delivery.domain.DeliveryAttemptState;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DeliveryAttemptGauges {

    private final MeterRegistry registry;
    private final DeliveryAttemptRepository attemptRepository;

    @PostConstruct
    void register() {
        Gauge.builder("delivery_attempt.queue.size", attemptRepository,
                r -> (double) r.countByState(DeliveryAttemptState.READY))
            .description("Number of READY delivery_attempt rows")
            .register(registry);

        Gauge.builder("delivery_attempt.lag", attemptRepository,
                r -> (double) r.computeLagSeconds())
            .description("Seconds since the oldest READY row became due (now - min(next_attempt_at))")
            .register(registry);

        Gauge.builder("delivery_attempt.claimed.stuck", attemptRepository,
                r -> (double) r.countStuckAttempts())
            .description("IN_PROGRESS rows with claimed_until < now (Reaper missed)")
            .register(registry);
    }
}
