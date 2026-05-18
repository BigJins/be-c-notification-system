package com.livenotification.integration.admin;

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
 * Verifies the admin-retry endpoint resurrects a DEAD delivery:
 * - delivery.state  → PENDING
 * - delivery.attempt_count preserved (monotone invariant)
 * - at least one new delivery_attempt row in state READY
 */
class AdminRetryIT extends AbstractIntegrationTest {

    @Autowired TestRestTemplate restTemplate;
    @Autowired ObjectMapper objectMapper;

    /** Admin token default value from application.yml (no override in test env). */
    private static final String ADMIN_TOKEN = "dev-token-do-not-use-in-prod";

    @Test
    void adminRetry_resurrectsDeadDelivery_preservesAttemptCount() throws Exception {
        // Step 1: register a transient-failure notification → will eventually become DEAD
        String body = objectMapper.writeValueAsString(TestNotificationFixtures.registerBody(
            "admin-e1", "u-admin", NotificationType.PAYMENT_CONFIRMED,
            List.of(ChannelType.EMAIL),
            TestNotificationFixtures.payloadWithTransientFailure(objectMapper)));

        HttpHeaders postH = new HttpHeaders();
        postH.setContentType(MediaType.APPLICATION_JSON);
        var reg = restTemplate.exchange(
            baseUrl() + "/v1/notifications",
            HttpMethod.POST,
            new HttpEntity<>(body, postH),
            Map.class);
        assertThat(reg.getStatusCode().value()).isEqualTo(202);
        UUID notificationId = UUID.fromString((String) reg.getBody().get("id"));

        // Step 2: wait until the delivery reaches DEAD (max-attempts=3, interval~200ms each)
        Awaitility.await()
            .atMost(Duration.ofSeconds(15))
            .pollInterval(Duration.ofMillis(200))
            .untilAsserted(() -> {
                String state = jdbcTemplate.queryForObject(
                    "SELECT state FROM delivery WHERE notification_id = ? AND channel = 'EMAIL'",
                    String.class, notificationId);
                assertThat(state).isEqualTo("DEAD");
            });

        int preRetryAttemptCount = jdbcTemplate.queryForObject(
            "SELECT attempt_count FROM delivery WHERE notification_id = ? AND channel = 'EMAIL'",
            Integer.class, notificationId);

        // Step 3: admin retry — POST /v1/admin/notifications/{id}/retry
        HttpHeaders adminH = new HttpHeaders();
        adminH.set("X-Admin-Token", ADMIN_TOKEN);
        var retryRes = restTemplate.exchange(
            baseUrl() + "/v1/admin/notifications/" + notificationId + "/retry",
            HttpMethod.POST,
            new HttpEntity<>(adminH),
            Void.class);
        assertThat(retryRes.getStatusCode().value()).isEqualTo(204);

        // Step 4: assert PENDING + attempt_count preserved + new READY attempt row
        Map<String, Object> delivery = jdbcTemplate.queryForMap(
            "SELECT state, attempt_count FROM delivery WHERE notification_id = ? AND channel = 'EMAIL'",
            notificationId);
        assertThat(delivery.get("state")).isEqualTo("PENDING");
        assertThat(((Number) delivery.get("attempt_count")).intValue())
            .as("attempt_count must be preserved (monotone invariant) after admin retry")
            .isEqualTo(preRetryAttemptCount);

        Integer ready = jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM delivery_attempt da
            JOIN delivery d ON d.id = da.delivery_id
            WHERE d.notification_id = ? AND da.state = 'READY'
            """, Integer.class, notificationId);
        assertThat(ready)
            .as("admin retry must create at least one new READY delivery_attempt")
            .isGreaterThanOrEqualTo(1);
    }
}
