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
 * Verifies that admin retry uses the original (immutable) payload — no payload tampering
 * is possible because the notification.payload column is INSERT-only (updatable=false).
 */
class AdminRetryUsesCurrentPayloadIT extends AbstractIntegrationTest {

    @Autowired TestRestTemplate restTemplate;
    @Autowired ObjectMapper objectMapper;

    private static final String ADMIN_TOKEN = "dev-token-do-not-use-in-prod";

    @Test
    void adminRetry_preservesOriginalPayload() throws Exception {
        // Register with permanent-failure payload so delivery goes DEAD immediately
        String body = objectMapper.writeValueAsString(TestNotificationFixtures.registerBody(
            "adminpl-1", "u-admin", NotificationType.PAYMENT_CONFIRMED,
            List.of(ChannelType.EMAIL),
            TestNotificationFixtures.payloadWithPermanentFailure(objectMapper)));
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        var res = restTemplate.exchange(baseUrl() + "/v1/notifications",
            HttpMethod.POST, new HttpEntity<>(body, h), Map.class);
        assertThat(res.getStatusCode().value()).isEqualTo(202);
        UUID id = UUID.fromString((String) res.getBody().get("id"));

        // Wait for delivery to reach DEAD
        Awaitility.await()
            .atMost(Duration.ofSeconds(5))
            .pollInterval(Duration.ofMillis(150))
            .untilAsserted(() -> assertThat(jdbcTemplate.queryForObject(
                "SELECT state FROM delivery WHERE notification_id = ? AND channel = 'EMAIL'",
                String.class, id)).isEqualTo("DEAD"));

        // Snapshot payload before admin retry
        String payloadBefore = jdbcTemplate.queryForObject(
            "SELECT payload::text FROM notification WHERE id = ?", String.class, id);

        // Admin retry
        HttpHeaders ah = new HttpHeaders();
        ah.set("X-Admin-Token", ADMIN_TOKEN);
        var retryRes = restTemplate.exchange(
            baseUrl() + "/v1/admin/notifications/" + id + "/retry",
            HttpMethod.POST, new HttpEntity<>(ah), Void.class);
        assertThat(retryRes.getStatusCode().value()).isEqualTo(204);

        // Payload must be identical after admin retry (updatable=false enforced at JPA level)
        String payloadAfter = jdbcTemplate.queryForObject(
            "SELECT payload::text FROM notification WHERE id = ?", String.class, id);
        assertThat(payloadAfter)
            .as("notification.payload must be immutable — unchanged after admin retry")
            .isEqualTo(payloadBefore);

        // Delivery should be reset to PENDING for the next dispatch cycle
        String deliveryState = jdbcTemplate.queryForObject(
            "SELECT state FROM delivery WHERE notification_id = ? AND channel = 'EMAIL'",
            String.class, id);
        assertThat(deliveryState).isEqualTo("PENDING");
    }
}
