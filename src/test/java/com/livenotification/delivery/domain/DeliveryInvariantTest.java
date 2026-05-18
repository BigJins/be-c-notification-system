package com.livenotification.delivery.domain;

import com.livenotification.notification.domain.NotificationId;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeliveryInvariantTest {

    private final Clock fixedClock = Clock.fixed(Instant.parse("2026-05-18T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void stateTransitions_email_pendingToSent_sentToDead_deadToPending() {
        // Invariant 1: forEmail → PENDING; markSent → SENT; markPending from SENT throws (must be DEAD).
        NotificationId notifId = new NotificationId(UUID.randomUUID());
        Delivery delivery = Delivery.forEmail(notifId, fixedClock);

        assertThat(delivery.getState()).isEqualTo(DeliveryState.PENDING);
        assertThat(delivery.getAttemptCount().value()).isZero();

        Instant now = fixedClock.instant();
        delivery.markSent(now);
        assertThat(delivery.getState()).isEqualTo(DeliveryState.SENT);
        assertThat(delivery.getAttemptCount().value()).isOne();

        // SENT → PENDING must throw (only DEAD → PENDING is valid)
        assertThatThrownBy(() -> delivery.markPending(now))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("DEAD");

        // forEmail → PENDING → DEAD → PENDING is the valid path
        Delivery d2 = Delivery.forEmail(notifId, fixedClock);
        d2.markDead("permanent failure", now);
        assertThat(d2.getState()).isEqualTo(DeliveryState.DEAD);
        d2.markPending(now);
        assertThat(d2.getState()).isEqualTo(DeliveryState.PENDING);
    }

    @Test
    void attemptCount_isMonotonicallyIncreasing_neverResets() {
        // Invariant 2: attempt_count monotonically increasing — markSent/markDead/recordTransientFailure increment;
        // markPending preserves count.
        NotificationId notifId = new NotificationId(UUID.randomUUID());
        Instant now = fixedClock.instant();

        // markSent increments
        Delivery d1 = Delivery.forEmail(notifId, fixedClock);
        assertThat(d1.getAttemptCount().value()).isZero();
        d1.markSent(now);
        assertThat(d1.getAttemptCount().value()).isOne();

        // markDead increments
        Delivery d2 = Delivery.forEmail(notifId, fixedClock);
        d2.markDead("error", now);
        assertThat(d2.getAttemptCount().value()).isOne();

        // recordTransientFailure increments
        Delivery d3 = Delivery.forEmail(notifId, fixedClock);
        d3.recordTransientFailure("transient", now);
        assertThat(d3.getAttemptCount().value()).isOne();

        // markPending (from DEAD) preserves count — count must NOT reset
        Delivery d4 = Delivery.forEmail(notifId, fixedClock);
        d4.recordTransientFailure("first", now);
        d4.markDead("permanent", now);
        int countBeforePending = d4.getAttemptCount().value();
        d4.markPending(now);
        assertThat(d4.getAttemptCount().value())
            .as("attempt_count must NOT decrease on markPending (monotonically increasing)")
            .isGreaterThanOrEqualTo(countBeforePending);
    }

    @Test
    void sentAt_notNull_iff_stateSent() {
        // Invariant 3: sent_at NOT NULL ↔ state=SENT; forEmail.markDead leaves sentAt null.
        NotificationId notifId = new NotificationId(UUID.randomUUID());
        Instant now = fixedClock.instant();

        Delivery emailDelivery = Delivery.forEmail(notifId, fixedClock);
        assertThat(emailDelivery.getSentAt()).isNull();

        emailDelivery.markSent(now);
        assertThat(emailDelivery.getSentAt()).isNotNull().isEqualTo(now);
        assertThat(emailDelivery.getState()).isEqualTo(DeliveryState.SENT);

        // markDead leaves sentAt null
        Delivery dead = Delivery.forEmail(notifId, fixedClock);
        dead.markDead("err", now);
        assertThat(dead.getSentAt()).isNull();
        assertThat(dead.getState()).isEqualTo(DeliveryState.DEAD);
    }

    @Test
    void inApp_immediatelySent_attemptCountAtLeastOne() {
        // Invariant 4: channel=IN_APP → state=SENT + attempt_count≥1 at creation time.
        NotificationId notifId = new NotificationId(UUID.randomUUID());
        Delivery inApp = Delivery.forInApp(notifId, fixedClock);

        assertThat(inApp.getChannel()).isEqualTo(ChannelType.IN_APP);
        assertThat(inApp.getState()).isEqualTo(DeliveryState.SENT);
        assertThat(inApp.getAttemptCount().value()).isGreaterThanOrEqualTo(1);
        assertThat(inApp.getSentAt()).isNotNull();
    }

    @Test
    void dedupKeyFields_areFinal_noSetter() {
        // Invariant 5: dedup key fields (notificationId, channel) are final — no setters exposed.
        boolean hasSetter = Arrays.stream(Delivery.class.getMethods())
            .anyMatch(m -> m.getName().startsWith("setNotificationId") ||
                           m.getName().startsWith("setChannel"));
        assertThat(hasSetter)
            .as("Delivery must NOT expose setNotificationId() or setChannel() — dedup key must be immutable")
            .isFalse();
    }
}
