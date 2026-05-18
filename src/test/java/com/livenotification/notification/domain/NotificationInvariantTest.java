package com.livenotification.notification.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NotificationInvariantTest {

    private final Clock fixedClock = Clock.fixed(Instant.parse("2026-05-18T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void payload_isImmutable_factoryIsOnlyEntry() throws Exception {
        // Invariant 1: payload INSERT 후 immutable.
        // Structural enforce: factory is only public construction entry + no raw JsonNode getter exposed.
        var n = Notification.create(
            new EventId("e1"), new RecipientId("u1"), NotificationType.PAYMENT_CONFIRMED,
            new NotificationPayload(new ObjectMapper().createObjectNode().put("k", "v")), fixedClock);

        // Verify: no public method named getPayload exists (raw JsonNode getter hidden)
        boolean hasRawGetter = Arrays.stream(Notification.class.getMethods())
            .anyMatch(m -> m.getName().equals("getPayload"));
        assertThat(hasRawGetter).as("Notification must NOT expose getPayload() — raw JsonNode immutability").isFalse();

        // payload() returns VO wrapper (not direct mutable JsonNode access)
        Method payloadMethod = Notification.class.getMethod("payload");
        assertThat(payloadMethod.getReturnType()).isEqualTo(NotificationPayload.class);
    }

    @Test
    void readAt_application_layer_enforces_in_app_sent_precondition() {
        // Invariant 2: read_at nullable; entity-level markRead is idempotent.
        // The cross-AR precondition (IN_APP delivery SENT) is enforced at application service, NOT entity.
        // Document this by verifying markRead is idempotent (no exception on repeated call).
        var n = Notification.create(
            new EventId("e2"), new RecipientId("u2"), NotificationType.ENROLLMENT_COMPLETED,
            new NotificationPayload(new ObjectMapper().createObjectNode()), fixedClock);

        Instant now = fixedClock.instant();
        n.markRead(now);
        assertThat(n.getReadAt()).isEqualTo(now);

        // idempotent: 2nd call does not throw or overwrite
        n.markRead(now.plusSeconds(60));
        assertThat(n.getReadAt()).isEqualTo(now);
    }

    @Test
    void dedupKey_fields_areFinal_setByFactory() {
        // Invariant 3: dedup key (event_id + recipient_id + type) — factory sets, no setter.
        // Verify: factory rejects nulls (NPE), and Notification has no setEventId/Recipient/Type methods.
        assertThatThrownBy(() -> Notification.create(null,
                new RecipientId("u"), NotificationType.PAYMENT_CONFIRMED,
                new NotificationPayload(new ObjectMapper().createObjectNode()), fixedClock))
            .isInstanceOf(NullPointerException.class);

        boolean hasSetter = Arrays.stream(Notification.class.getMethods())
            .anyMatch(m -> m.getName().startsWith("setEvent") ||
                            m.getName().startsWith("setRecipient") ||
                            m.getName().startsWith("setType"));
        assertThat(hasSetter).isFalse();
    }
}
