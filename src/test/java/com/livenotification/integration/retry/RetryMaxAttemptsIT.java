package com.livenotification.integration.retry;

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

class RetryMaxAttemptsIT extends AbstractIntegrationTest {

    @Autowired TestRestTemplate restTemplate;
    @Autowired ObjectMapper objectMapper;

    @Test
    void transientFailure_exhaustsMaxAttempts_thenDead() throws Exception {
        String body = objectMapper.writeValueAsString(TestNotificationFixtures.registerBody(
            "retry-max-1", "u1", NotificationType.PAYMENT_CONFIRMED,
            List.of(ChannelType.EMAIL),
            TestNotificationFixtures.payloadWithTransientFailure(objectMapper)));
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        var res = restTemplate.exchange(baseUrl() + "/v1/notifications",
            HttpMethod.POST, new HttpEntity<>(body, h), Map.class);
        assertThat(res.getStatusCode().value()).isEqualTo(202);
        UUID notificationId = UUID.fromString((String) res.getBody().get("id"));

        Awaitility.await().atMost(Duration.ofSeconds(15))
            .pollInterval(Duration.ofMillis(200))
            .untilAsserted(() -> {
                Map<String, Object> delivery = jdbcTemplate.queryForMap(
                    "SELECT state, attempt_count FROM delivery WHERE notification_id = ? AND channel = 'EMAIL'",
                    notificationId);
                assertThat(delivery.get("state")).isEqualTo("DEAD");
                assertThat(((Number) delivery.get("attempt_count")).intValue()).isGreaterThanOrEqualTo(3);
            });

        // At least 1 FAILED delivery_attempt row
        Integer failed = jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM delivery_attempt da
            JOIN delivery d ON d.id = da.delivery_id
            WHERE d.notification_id = ? AND da.state = 'FAILED'
            """, Integer.class, notificationId);
        assertThat(failed).isGreaterThanOrEqualTo(1);
    }
}
