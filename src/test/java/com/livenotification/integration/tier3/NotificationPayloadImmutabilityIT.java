package com.livenotification.integration.tier3;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.livenotification.delivery.domain.ChannelType;
import com.livenotification.integration.support.AbstractIntegrationTest;
import com.livenotification.integration.support.TestNotificationFixtures;
import com.livenotification.notification.domain.NotificationType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationPayloadImmutabilityIT extends AbstractIntegrationTest {

    @Autowired TestRestTemplate restTemplate;
    @Autowired ObjectMapper objectMapper;

    @Test
    void notification_hasNoSetPayloadOrMutator() {
        // Structural enforcement: no setter, no mutator method on Notification entity
        boolean hasSetterOrMutator = Arrays.stream(
                com.livenotification.notification.domain.Notification.class.getMethods())
            .anyMatch(m -> m.getName().equals("setPayload")
                || m.getName().equals("mutatePayload")
                || m.getName().equals("updatePayload"));
        assertThat(hasSetterOrMutator)
            .as("Notification must not expose any payload-mutating method")
            .isFalse();
    }

    @Test
    void payloadAccessor_returnsUnchangedPayload_onRepeatedGet() throws Exception {
        String body = objectMapper.writeValueAsString(TestNotificationFixtures.registerBody(
            "imm-1", "u-imm", NotificationType.PAYMENT_CONFIRMED,
            List.of(ChannelType.EMAIL),
            TestNotificationFixtures.payloadNormal(objectMapper)));
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        var res = restTemplate.exchange(baseUrl() + "/v1/notifications",
            HttpMethod.POST, new HttpEntity<>(body, h), Map.class);
        String id = (String) res.getBody().get("id");

        // First GET
        var get1 = restTemplate.exchange(baseUrl() + "/v1/notifications/" + id,
            HttpMethod.GET, HttpEntity.EMPTY, Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> payload1 = (Map<String, Object>) get1.getBody().get("payload");
        Object originalSubject = payload1.get("subject");

        // Second GET — payload must be identical
        var get2 = restTemplate.exchange(baseUrl() + "/v1/notifications/" + id,
            HttpMethod.GET, HttpEntity.EMPTY, Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> payload2 = (Map<String, Object>) get2.getBody().get("payload");
        assertThat(payload2.get("subject"))
            .as("payload subject must be unchanged on repeated GET")
            .isEqualTo(originalSubject);
        assertThat(payload2.get("body"))
            .as("payload body must be unchanged on repeated GET")
            .isEqualTo(payload1.get("body"));
    }
}
