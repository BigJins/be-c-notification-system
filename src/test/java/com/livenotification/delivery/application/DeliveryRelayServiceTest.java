package com.livenotification.delivery.application;

import com.livenotification.delivery.application.metrics.DeliveryMetrics;
import com.livenotification.delivery.adapter.out.channel.InAppAdapter;
import com.livenotification.delivery.application.port.ChannelAdapter;
import com.livenotification.delivery.application.port.ChannelAdapterRouter;
import com.livenotification.delivery.domain.ChannelType;
import com.livenotification.delivery.domain.Delivery;
import com.livenotification.delivery.domain.DeliveryAttempt;
import com.livenotification.delivery.domain.DeliveryAttemptId;
import com.livenotification.delivery.domain.DeliveryAttemptState;
import com.livenotification.delivery.domain.DeliveryState;
import com.livenotification.delivery.domain.DispatchResult;
import com.livenotification.delivery.domain.RetryPolicy;
import com.livenotification.global.config.NotificationProperties;
import com.livenotification.notification.application.NotificationLookup;
import com.livenotification.notification.application.NotificationView;
import com.livenotification.notification.domain.EventId;
import com.livenotification.notification.domain.NotificationId;
import com.livenotification.notification.domain.NotificationPayload;
import com.livenotification.notification.domain.NotificationType;
import com.livenotification.notification.domain.RecipientId;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DeliveryRelayServiceTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-05-19T00:00:00Z"), ZoneOffset.UTC);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void relay_corruptInAppAttempt_terminatesAttemptWithoutChangingDelivery() throws Exception {
        DeliveryRepository deliveryRepository = mock(DeliveryRepository.class);
        DeliveryAttemptRepository attemptRepository = mock(DeliveryAttemptRepository.class);
        ChannelAdapterRouter channelRouter = mock(ChannelAdapterRouter.class);
        NotificationLookup notificationLookup = mock(NotificationLookup.class);
        RetryPolicy retryPolicy = new RetryPolicy(Duration.ofMillis(100), 3, 0.0);
        NotificationProperties properties = properties();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Delivery delivery = Delivery.forInApp(new NotificationId(UUID.randomUUID()), clock);
            DeliveryAttempt attempt = DeliveryAttempt.readyFor(delivery.getId(), clock);
            attempt.claim("worker-1", clock.instant(), clock.instant().plusSeconds(30));
            NotificationView view = notificationView(delivery.getNotificationId());

            when(attemptRepository.findById(attempt.getId().value())).thenReturn(Optional.of(attempt));
            when(deliveryRepository.findById(delivery.getId().value())).thenReturn(Optional.of(delivery));
            when(notificationLookup.findById(delivery.getNotificationId())).thenReturn(Optional.of(view));
            when(channelRouter.route(ChannelType.IN_APP)).thenReturn(new InAppAdapter(new DeliveryMetrics(new SimpleMeterRegistry())));

            DeliveryRelayService service = new DeliveryRelayService(
                deliveryRepository,
                attemptRepository,
                channelRouter,
                notificationLookup,
                retryPolicy,
                properties,
                executor,
                clock,
                new DeliveryMetrics(new SimpleMeterRegistry()));

            service.relay(attempt.getId());

            assertThat(attempt.getState()).isEqualTo(DeliveryAttemptState.FAILED);
            assertThat(attempt.getAttemptCount().value()).isEqualTo(1);
            assertThat(attempt.getLastError()).isEqualTo("inapp_dispatch_attempted");
            assertThat(delivery.getState()).isEqualTo(DeliveryState.SENT);
            assertThat(delivery.getAttemptCount().value()).isEqualTo(1);
            assertThat(delivery.getSentAt()).isNotNull();
        } finally {
            executor.close();
        }
    }

    /**
     * Adapter contract violation guard (ADR-0001 / docs/document.md §5).
     * If an adapter throws despite the contract, DeliveryRelayService must:
     *   (1) classify the throw as PermanentFailure("adapter_contract_violation", cause)
     *       — observed via the reason string flowing into delivery + attempt lastError,
     *   (2) increment notification.design.violation{kind=adapter_contract_violation} once,
     *   (3) terminate delivery=DEAD and attempt=FAILED so a silent retry loop can't hide the bug.
     */
    @Test
    void relay_adapterThrows_classifiedAsContractViolationAndKillsDelivery() throws Exception {
        DeliveryRepository deliveryRepository = mock(DeliveryRepository.class);
        DeliveryAttemptRepository attemptRepository = mock(DeliveryAttemptRepository.class);
        ChannelAdapterRouter channelRouter = mock(ChannelAdapterRouter.class);
        NotificationLookup notificationLookup = mock(NotificationLookup.class);
        RetryPolicy retryPolicy = new RetryPolicy(Duration.ofMillis(100), 3, 0.0);
        NotificationProperties properties = properties();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        DeliveryMetrics metrics = new DeliveryMetrics(meterRegistry);
        try {
            Delivery delivery = Delivery.forEmail(new NotificationId(UUID.randomUUID()), clock);
            DeliveryAttempt attempt = DeliveryAttempt.readyFor(delivery.getId(), clock);
            attempt.claim("worker-1", clock.instant(), clock.instant().plusSeconds(30));
            NotificationView view = notificationView(delivery.getNotificationId());

            when(attemptRepository.findById(attempt.getId().value())).thenReturn(Optional.of(attempt));
            when(deliveryRepository.findById(delivery.getId().value())).thenReturn(Optional.of(delivery));
            when(notificationLookup.findById(delivery.getNotificationId())).thenReturn(Optional.of(view));
            when(channelRouter.route(ChannelType.EMAIL)).thenReturn(throwingEmailAdapter());

            DeliveryRelayService service = new DeliveryRelayService(
                deliveryRepository,
                attemptRepository,
                channelRouter,
                notificationLookup,
                retryPolicy,
                properties,
                executor,
                clock,
                metrics);

            service.relay(attempt.getId());

            assertThat(attempt.getLastError()).isEqualTo("adapter_contract_violation");
            assertThat(delivery.getLastError()).isEqualTo("adapter_contract_violation");

            assertThat(meterRegistry.counter("notification.design.violation",
                    "kind", "adapter_contract_violation").count())
                .isEqualTo(1.0);

            assertThat(attempt.getState()).isEqualTo(DeliveryAttemptState.FAILED);
            assertThat(delivery.getState()).isEqualTo(DeliveryState.DEAD);
        } finally {
            executor.close();
        }
    }

    private ChannelAdapter throwingEmailAdapter() {
        return new ChannelAdapter() {
            @Override
            public ChannelType type() {
                return ChannelType.EMAIL;
            }

            @Override
            public DispatchResult send(NotificationView notification, Delivery delivery) {
                throw new RuntimeException("simulated adapter bug — must not be silently retried");
            }
        };
    }

    private NotificationView notificationView(NotificationId notificationId) {
        return new NotificationView(
            notificationId,
            new EventId("event-1"),
            new RecipientId("user-1"),
            NotificationType.PAYMENT_CONFIRMED,
            new NotificationPayload(objectMapper.createObjectNode().put("subject", "x")),
            null);
    }

    private NotificationProperties properties() {
        return new NotificationProperties(
            new NotificationProperties.Retry(Duration.ofMillis(100), 3, 0.0),
            new NotificationProperties.Worker(
                Duration.ofMillis(200),
                4,
                4,
                Duration.ofSeconds(30),
                Duration.ofSeconds(30),
                Duration.ofMillis(500)),
            new NotificationProperties.Admin("token"),
            new NotificationProperties.Cleanup(Duration.ofDays(30), Duration.ofHours(24)));
    }
}
