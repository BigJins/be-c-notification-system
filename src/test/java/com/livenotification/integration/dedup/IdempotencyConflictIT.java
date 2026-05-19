package com.livenotification.integration.dedup;

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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IdempotencyConflictIT extends AbstractIntegrationTest {

    @Autowired TestRestTemplate restTemplate;
    @Autowired ObjectMapper objectMapper;

    @Test
    void sameKeyDifferentBody_returns409WithProblemDetail() throws Exception {
        // Body A: event=conflict-event-1
        String bodyA = objectMapper.writeValueAsString(TestNotificationFixtures.registerBody(
            "conflict-event-1", "u1", NotificationType.PAYMENT_CONFIRMED,
            List.of(ChannelType.EMAIL),
            TestNotificationFixtures.payloadNormal(objectMapper)));

        // Body B: different event id → different request hash
        String bodyB = objectMapper.writeValueAsString(TestNotificationFixtures.registerBody(
            "conflict-event-2", "u1", NotificationType.ENROLLMENT_COMPLETED,
            List.of(ChannelType.EMAIL),
            TestNotificationFixtures.payloadNormal(objectMapper)));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", "conflict-key-1");

        // First POST: body A → 202
        var resA = restTemplate.exchange(
            baseUrl() + "/v1/notifications",
            HttpMethod.POST,
            new HttpEntity<>(bodyA, headers),
            String.class);
        assertThat(resA.getStatusCode().value())
            .as("first POST should be 202 Accepted")
            .isEqualTo(202);

        // Second POST: same key, body B (different) → 409
        var resB = restTemplate.exchange(
            baseUrl() + "/v1/notifications",
            HttpMethod.POST,
            new HttpEntity<>(bodyB, headers),
            String.class);
        assertThat(resB.getStatusCode().value())
            .as("second POST with different body under same key should be 409 Conflict")
            .isEqualTo(409);
        assertThat(resB.getBody())
            .as("ProblemDetail type should contain idempotency-conflict slug")
            .contains("idempotency-conflict");
    }
}
