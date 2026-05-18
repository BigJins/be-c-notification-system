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

class RetrySuccessIT extends AbstractIntegrationTest {

    @Autowired TestRestTemplate restTemplate;
    @Autowired ObjectMapper objectMapper;

    @Test
    void normalEmail_dispatchesToSent_withinPollInterval() throws Exception {
        String body = objectMapper.writeValueAsString(TestNotificationFixtures.registerBody(
            "retry-success-1", "u1", NotificationType.PAYMENT_CONFIRMED,
            List.of(ChannelType.EMAIL),
            TestNotificationFixtures.payloadNormal(objectMapper)));

        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        var res = restTemplate.exchange(baseUrl() + "/v1/notifications",
            HttpMethod.POST, new HttpEntity<>(body, h), Map.class);
        assertThat(res.getStatusCode().value()).isEqualTo(202);
        UUID notificationId = UUID.fromString((String) res.getBody().get("id"));

        Awaitility.await().atMost(Duration.ofSeconds(3))
            .untilAsserted(() -> {
                String state = jdbcTemplate.queryForObject(
                    "SELECT state FROM delivery WHERE notification_id = ? AND channel = 'EMAIL'",
                    String.class, notificationId);
                assertThat(state).isEqualTo("SENT");
            });
    }
}
