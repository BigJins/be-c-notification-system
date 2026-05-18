package com.livenotification.idempotency.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.livenotification.delivery.domain.ChannelType;
import com.livenotification.notification.application.RegisterCommand;
import com.livenotification.notification.domain.EventId;
import com.livenotification.notification.domain.NotificationPayload;
import com.livenotification.notification.domain.NotificationType;
import com.livenotification.notification.domain.RecipientId;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequestHashTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void regex_acceptsValid_lowercaseHex64() {
        String valid = "a".repeat(64);
        assertThat(new RequestHash(valid).value()).isEqualTo(valid);
    }

    @Test
    void regex_rejectsInvalid() {
        assertThatThrownBy(() -> new RequestHash(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RequestHash("not-hex"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RequestHash("A".repeat(64)))   // uppercase not allowed
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void of_deterministicForSameCommand() {
        RegisterCommand cmd = makeCmd();
        RequestHash h1 = RequestHash.of(cmd, mapper);
        RequestHash h2 = RequestHash.of(cmd, mapper);
        assertThat(h1).isEqualTo(h2);
    }

    @Test
    void of_payloadKeyOrderIndependent() {
        ObjectNode p1 = mapper.createObjectNode();
        p1.put("subject", "s"); p1.put("body", "b");
        ObjectNode p2 = mapper.createObjectNode();
        p2.put("body", "b"); p2.put("subject", "s");

        RegisterCommand cmd1 = new RegisterCommand(
            new EventId("e"), new RecipientId("u"), NotificationType.PAYMENT_CONFIRMED,
            List.of(ChannelType.EMAIL), new NotificationPayload(p1));
        RegisterCommand cmd2 = new RegisterCommand(
            new EventId("e"), new RecipientId("u"), NotificationType.PAYMENT_CONFIRMED,
            List.of(ChannelType.EMAIL), new NotificationPayload(p2));

        assertThat(RequestHash.of(cmd1, mapper)).isEqualTo(RequestHash.of(cmd2, mapper));
    }

    @Test
    void of_channelsOrderIndependent() {
        ObjectNode p = mapper.createObjectNode().put("k", "v");
        RegisterCommand cmd1 = new RegisterCommand(
            new EventId("e"), new RecipientId("u"), NotificationType.PAYMENT_CONFIRMED,
            List.of(ChannelType.EMAIL, ChannelType.IN_APP), new NotificationPayload(p));
        RegisterCommand cmd2 = new RegisterCommand(
            new EventId("e"), new RecipientId("u"), NotificationType.PAYMENT_CONFIRMED,
            List.of(ChannelType.IN_APP, ChannelType.EMAIL), new NotificationPayload(p));

        assertThat(RequestHash.of(cmd1, mapper)).isEqualTo(RequestHash.of(cmd2, mapper));
    }

    private RegisterCommand makeCmd() {
        return new RegisterCommand(
            new EventId("e1"), new RecipientId("u1"), NotificationType.PAYMENT_CONFIRMED,
            List.of(ChannelType.EMAIL), new NotificationPayload(mapper.createObjectNode().put("k", "v")));
    }
}
