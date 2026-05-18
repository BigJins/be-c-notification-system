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

class DeliveryAttemptCountCumulativeIT extends AbstractIntegrationTest {

    @Autowired TestRestTemplate restTemplate;
    @Autowired ObjectMapper objectMapper;

    @Test
    void transientFailures_accumulateAttemptCount() throws Exception {
        String body = objectMapper.writeValueAsString(TestNotificationFixtures.registerBody(
            "cumulative-1", "u1", NotificationType.PAYMENT_CONFIRMED,
            List.of(ChannelType.EMAIL),
            TestNotificationFixtures.payloadWithTransientFailure(objectMapper)));
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        var res = restTemplate.exchange(baseUrl() + "/v1/notifications",
            HttpMethod.POST, new HttpEntity<>(body, h), Map.class);
        UUID notificationId = UUID.fromString((String) res.getBody().get("id"));

        Awaitility.await().atMost(Duration.ofSeconds(15))
            .untilAsserted(() -> {
                Integer attempt = jdbcTemplate.queryForObject(
                    "SELECT attempt_count FROM delivery WHERE notification_id = ? AND channel = 'EMAIL'",
                    Integer.class, notificationId);
                assertThat(attempt).isGreaterThanOrEqualTo(3);
            });
    }
}
