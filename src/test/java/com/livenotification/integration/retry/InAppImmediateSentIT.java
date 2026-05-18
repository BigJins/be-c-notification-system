package com.livenotification.integration.retry;

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
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class InAppImmediateSentIT extends AbstractIntegrationTest {

    @Autowired TestRestTemplate restTemplate;
    @Autowired ObjectMapper objectMapper;

    @Test
    void inAppChannel_isSentImmediately_noPolling() throws Exception {
        String body = objectMapper.writeValueAsString(TestNotificationFixtures.registerBody(
            "inapp-1", "u1", NotificationType.PAYMENT_CONFIRMED,
            List.of(ChannelType.IN_APP),
            TestNotificationFixtures.payloadNormal(objectMapper)));
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        var res = restTemplate.exchange(baseUrl() + "/v1/notifications",
            HttpMethod.POST, new HttpEntity<>(body, h), Map.class);
        UUID notificationId = UUID.fromString((String) res.getBody().get("id"));

        Map<String, Object> delivery = jdbcTemplate.queryForMap(
            "SELECT state, attempt_count, sent_at FROM delivery WHERE notification_id = ? AND channel = 'IN_APP'",
            notificationId);
        assertThat(delivery.get("state")).isEqualTo("SENT");
        assertThat(((Number) delivery.get("attempt_count")).intValue()).isEqualTo(1);
        assertThat(delivery.get("sent_at")).isNotNull();

        String attemptState = jdbcTemplate.queryForObject("""
            SELECT da.state FROM delivery_attempt da
            JOIN delivery d ON d.id = da.delivery_id
            WHERE d.notification_id = ? AND d.channel = 'IN_APP'
            """, String.class, notificationId);
        assertThat(attemptState).isEqualTo("DONE");
    }
}
