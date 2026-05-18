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

class StateNonCorrespondenceIT extends AbstractIntegrationTest {

    @Autowired TestRestTemplate restTemplate;
    @Autowired ObjectMapper objectMapper;

    @Test
    void duringTransientRetry_deliveryStateAndAttemptStateDiverge() throws Exception {
        String body = objectMapper.writeValueAsString(TestNotificationFixtures.registerBody(
            "diverge-1", "u1", NotificationType.PAYMENT_CONFIRMED,
            List.of(ChannelType.EMAIL),
            TestNotificationFixtures.payloadWithTransientFailure(objectMapper)));
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        var res = restTemplate.exchange(baseUrl() + "/v1/notifications",
            HttpMethod.POST, new HttpEntity<>(body, h), Map.class);
        UUID notificationId = UUID.fromString((String) res.getBody().get("id"));

        // Eventually we see a delivery_attempt with attempt_count > 0 (retry happened)
        // while at some point delivery_attempt exists in non-DONE state
        Awaitility.await().atMost(Duration.ofSeconds(10))
            .untilAsserted(() -> {
                Integer count = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM delivery_attempt da
                    JOIN delivery d ON d.id = da.delivery_id
                    WHERE d.notification_id = ?
                      AND da.state IN ('FAILED', 'READY')
                      AND da.attempt_count > 0
                    """, Integer.class, notificationId);
                assertThat(count).isGreaterThan(0);
            });
    }
}
