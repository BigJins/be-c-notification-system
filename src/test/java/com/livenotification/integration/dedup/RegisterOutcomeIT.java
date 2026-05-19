package com.livenotification.integration.dedup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.livenotification.delivery.domain.ChannelType;
import com.livenotification.idempotency.domain.IdempotencyKey;
import com.livenotification.integration.support.AbstractIntegrationTest;
import com.livenotification.integration.support.TestNotificationFixtures;
import com.livenotification.notification.application.NotificationService;
import com.livenotification.notification.application.RegisterCommand;
import com.livenotification.notification.application.RegisterOutcome;
import com.livenotification.notification.domain.EventId;
import com.livenotification.notification.domain.NotificationPayload;
import com.livenotification.notification.domain.NotificationType;
import com.livenotification.notification.domain.RecipientId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks the {@link RegisterOutcome} sealed variants at the service layer
 * (the controller side is covered by IdempotencyReplayIT / HeaderAndEventCompositionIT).
 * Each scenario must return exactly one variant; the compiler enforces switch
 * exhaustiveness, this IT enforces the mapping is the one ADR-0002 specifies.
 */
class RegisterOutcomeIT extends AbstractIntegrationTest {

    @Autowired NotificationService service;
    @Autowired ObjectMapper objectMapper;

    @Test
    void newEvent_noKey_returnsNewlyCreated() {
        RegisterOutcome outcome = service.register(command("ro-A", "u-ro-A"), null);
        assertThat(outcome).isInstanceOf(RegisterOutcome.NewlyCreated.class);
    }

    @Test
    void duplicateEvent_noKey_returnsEventDuplicate() {
        RegisterCommand cmd = command("ro-B", "u-ro-B");
        service.register(cmd, null);

        RegisterOutcome second = service.register(cmd, null);
        assertThat(second).isInstanceOf(RegisterOutcome.EventDuplicate.class);
        assertThat(second.detail().notification().getEventId().value()).isEqualTo("ro-B");
    }

    @Test
    void sameKey_sameBody_secondCallReturnsIdempotentReplay() {
        IdempotencyKey key = new IdempotencyKey("ro-key-C");
        RegisterCommand cmd = command("ro-C", "u-ro-C");

        RegisterOutcome first = service.register(cmd, key);
        assertThat(first).isInstanceOf(RegisterOutcome.NewlyCreated.class);

        RegisterOutcome second = service.register(cmd, key);
        assertThat(second).isInstanceOf(RegisterOutcome.IdempotentReplay.class);
        assertThat(second.detail().notification().getId())
            .isEqualTo(first.detail().notification().getId());
    }

    private RegisterCommand command(String eventId, String recipientId) {
        return new RegisterCommand(
            new EventId(eventId),
            new RecipientId(recipientId),
            NotificationType.PAYMENT_CONFIRMED,
            List.of(ChannelType.EMAIL),
            new NotificationPayload(TestNotificationFixtures.payloadNormal(objectMapper)));
    }
}
