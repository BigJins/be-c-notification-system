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
 * Verifies multi-channel fan-out semantics:
 * - channels=[EMAIL, IN_APP] produces exactly 1 notification + 2 deliveries
 * - IN_APP delivery is SENT immediately (same transaction, no worker)
 * - EMAIL delivery transitions to SENT via the dispatch worker
 */
class MultiChannelFanOutIT extends AbstractIntegrationTest {

    @Autowired TestRestTemplate restTemplate;
    @Autowired ObjectMapper objectMapper;

    @Test
    void registerWithBothChannels_creates1Notification_2Deliveries_correctStates() throws Exception {
        String body = objectMapper.writeValueAsString(TestNotificationFixtures.registerBody(
            "fanout-1", "u-fanout", NotificationType.PAYMENT_CONFIRMED,
            List.of(ChannelType.EMAIL, ChannelType.IN_APP),
            TestNotificationFixtures.payloadNormal(objectMapper)));
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        var res = restTemplate.exchange(baseUrl() + "/v1/notifications",
            HttpMethod.POST, new HttpEntity<>(body, h), Map.class);
        assertThat(res.getStatusCode().value()).isEqualTo(202);
        UUID id = UUID.fromString((String) res.getBody().get("id"));

        // Exactly 1 notification row
        Integer notifCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM notification WHERE id = ?", Integer.class, id);
        assertThat(notifCount).isEqualTo(1);

        // Exactly 2 delivery rows
        Integer deliveryCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM delivery WHERE notification_id = ?", Integer.class, id);
        assertThat(deliveryCount)
            .as("fan-out to [EMAIL, IN_APP] must create exactly 2 delivery rows")
            .isEqualTo(2);

        // IN_APP delivery is immediately SENT — no polling required
        String inAppState = jdbcTemplate.queryForObject(
            "SELECT state FROM delivery WHERE notification_id = ? AND channel = 'IN_APP'",
            String.class, id);
        assertThat(inAppState)
            .as("IN_APP delivery must be SENT immediately after registration")
            .isEqualTo("SENT");

        // IN_APP delivery_attempt must also be DONE immediately
        String inAppAttemptState = jdbcTemplate.queryForObject("""
            SELECT da.state FROM delivery_attempt da
            JOIN delivery d ON d.id = da.delivery_id
            WHERE d.notification_id = ? AND d.channel = 'IN_APP'
            """, String.class, id);
        assertThat(inAppAttemptState)
            .as("IN_APP delivery_attempt must be DONE in the same transaction")
            .isEqualTo("DONE");

        // EMAIL delivery eventually transitions to SENT via the dispatch worker
        Awaitility.await()
            .atMost(Duration.ofSeconds(3))
            .pollInterval(Duration.ofMillis(150))
            .untilAsserted(() -> {
                String emailState = jdbcTemplate.queryForObject(
                    "SELECT state FROM delivery WHERE notification_id = ? AND channel = 'EMAIL'",
                    String.class, id);
                assertThat(emailState)
                    .as("EMAIL delivery must reach SENT via worker dispatch")
                    .isEqualTo("SENT");
            });
    }
}
