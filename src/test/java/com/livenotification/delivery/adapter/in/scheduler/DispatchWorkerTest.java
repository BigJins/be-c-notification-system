package com.livenotification.delivery.adapter.in.scheduler;

import com.livenotification.delivery.application.DeliveryRelayService;
import com.livenotification.delivery.domain.DeliveryAttemptId;
import com.livenotification.global.config.NotificationProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DispatchWorkerTest {

    @Test
    void tick_claimsOnlyUpToAvailablePermits() {
        DeliveryRelayService relayService = mock(DeliveryRelayService.class);
        NotificationProperties properties = properties(8);
        Semaphore semaphore = new Semaphore(2, true);
        ExecutorService executor = mock(ExecutorService.class);
        when(relayService.claimBatch(anyInt(), anyString(), any()))
            .thenReturn(List.of(new DeliveryAttemptId(UUID.randomUUID())));

        DispatchWorker worker = new DispatchWorker(relayService, properties, semaphore, executor);

        worker.tick();

        verify(relayService).claimBatch(2, "worker-" + ProcessHandle.current().pid(), Duration.ofSeconds(30));
    }

    @Test
    void tick_skipsClaimWhenNoPermitsAvailable() {
        DeliveryRelayService relayService = mock(DeliveryRelayService.class);
        NotificationProperties properties = properties(8);
        Semaphore semaphore = new Semaphore(0, true);
        ExecutorService executor = mock(ExecutorService.class);

        DispatchWorker worker = new DispatchWorker(relayService, properties, semaphore, executor);

        worker.tick();

        verify(relayService, never()).claimBatch(anyInt(), anyString(), any());
    }

    private NotificationProperties properties(int batchSize) {
        return new NotificationProperties(
            new NotificationProperties.Retry(Duration.ofMillis(100), 3, 0.0),
            new NotificationProperties.Worker(
                Duration.ofMillis(200),
                batchSize,
                4,
                Duration.ofSeconds(30),
                Duration.ofSeconds(30),
                Duration.ofMillis(500)),
            new NotificationProperties.Admin("token"),
            new NotificationProperties.Cleanup(Duration.ofDays(30), Duration.ofHours(24)));
    }
}
