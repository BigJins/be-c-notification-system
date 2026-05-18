package com.livenotification.integration.tier3;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.livenotification.delivery.domain.ChannelType;
import com.livenotification.integration.support.AbstractIntegrationTest;
import com.livenotification.integration.support.TestNotificationFixtures;
import com.livenotification.notification.domain.NotificationType;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies partial channel failure isolation:
 * - channels=[EMAIL, IN_APP] + permanent-failure payload
 * - IN_APP must still be immediately SENT (worker not involved)
 * - EMAIL must eventually reach DEAD (permanent failure = no retry)
 * - The two delivery outcomes are independent
 */
class PartialChannelFailureIT extends AbstractIntegrationTest {

    @Autowired TestRestTemplate restTemplate;
    @Autowired ObjectMapper objectMapper;

    @Test
    void mixedChannels_permanentEmailFailure_inAppSentEmailDead() throws Exception {
        String body = objectMapper.writeValueAsString(TestNotificationFixtures.registerBody(
            "partial-1", "u-partial", NotificationType.PAYMENT_CONFIRMED,
            List.of(ChannelType.EMAIL, ChannelType.IN_APP),
            TestNotificationFixtures.payloadWithPermanentFailure(objectMapper)));
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        var res = restTemplate.exchange(baseUrl() + "/v1/notifications",
            HttpMethod.POST, new HttpEntity<>(body, h), Map.class);
        assertThat(res.getStatusCode().value()).isEqualTo(202);
        UUID id = UUID.fromString((String) res.getBody().get("id"));

        // IN_APP delivery must be SENT immediately — permanent failure in EMAIL adapter
        // does NOT block the IN_APP channel (fan-out isolation)
        String inAppState = jdbcTemplate.queryForObject(
            "SELECT state FROM delivery WHERE notification_id = ? AND channel = 'IN_APP'",
            String.class, id);
        assertThat(inAppState)
            .as("IN_APP delivery must be SENT immediately regardless of EMAIL failure")
            .isEqualTo("SENT");

        // EMAIL delivery must reach DEAD (permanent failure → no retry)
        Awaitility.await()
            .atMost(Duration.ofSeconds(5))
            .pollInterval(Duration.ofMillis(150))
            .untilAsserted(() -> {
                String emailState = jdbcTemplate.queryForObject(
                    "SELECT state FROM delivery WHERE notification_id = ? AND channel = 'EMAIL'",
                    String.class, id);
                assertThat(emailState)
                    .as("EMAIL delivery must be DEAD after permanent failure")
                    .isEqualTo("DEAD");
            });

        // Confirm IN_APP is still SENT (not affected by EMAIL going DEAD)
        String inAppStateFinal = jdbcTemplate.queryForObject(
            "SELECT state FROM delivery WHERE notification_id = ? AND channel = 'IN_APP'",
            String.class, id);
        assertThat(inAppStateFinal)
            .as("IN_APP state must remain SENT after EMAIL goes DEAD")
            .isEqualTo("SENT");
    }
}
