package com.livenotification.delivery.adapter.out.channel;

import com.livenotification.delivery.application.port.ChannelAdapter;
import com.livenotification.delivery.domain.ChannelType;
import com.livenotification.delivery.domain.Delivery;
import com.livenotification.delivery.domain.DispatchResult;
import com.livenotification.notification.application.NotificationView;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * IN_APP delivery is created already SENT (invariant #4). Worker entry here means
 * the design has been violated. Contract preserves DispatchResult while signaling:
 * - notification.design.violation counter +1 (kind=inapp_dispatch_attempted)
 * - log.error
 * - PermanentFailure return (this attempt becomes DEAD)
 *
 * Task 26 will replace direct MeterRegistry use with DeliveryMetrics.recordDesignViolation(...).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class InAppAdapter implements ChannelAdapter {

    private final MeterRegistry registry;

    @Override
    public ChannelType type() {
        return ChannelType.IN_APP;
    }

    @Override
    public DispatchResult send(NotificationView notification, Delivery delivery) {
        registry.counter("notification.design.violation",
            "kind", "inapp_dispatch_attempted").increment();

        IllegalStateException violation = new IllegalStateException(
            "InAppAdapter.send invoked for delivery=" + delivery.getId().value()
                + " — design violation: IN_APP delivery must be created already SENT (invariant #4).");
        log.error("DESIGN_VIOLATION: InAppAdapter.send invoked unexpectedly, deliveryId={}",
            delivery.getId().value(), violation);
        return new DispatchResult.PermanentFailure("inapp_dispatch_attempted", violation);
    }
}
