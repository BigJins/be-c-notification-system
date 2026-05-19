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

class EventDedupNoHeaderIT extends AbstractIntegrationTest {

    @Autowired TestRestTemplate restTemplate;
    @Autowired ObjectMapper objectMapper;

    @Test
    void sameEventIdTwice_firstAccepted_secondIsDuplicate() throws Exception {
        String body = objectMapper.writeValueAsString(TestNotificationFixtures.registerBody(
            "dedup-event-seq-1", "u1", NotificationType.PAYMENT_CONFIRMED,
            List.of(ChannelType.EMAIL),
            TestNotificationFixtures.payloadNormal(objectMapper)));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        // First POST — no Idempotency-Key header
        var res1 = restTemplate.exchange(
            baseUrl() + "/v1/notifications",
            HttpMethod.POST,
            new HttpEntity<>(body, headers),
            String.class);

        assertThat(res1.getStatusCode().value())
            .as("first request should be 202 Accepted")
            .isEqualTo(202);
        assertThat(res1.getHeaders().getFirst("X-Event-Duplicate"))
            .as("first request: X-Event-Duplicate should be false")
            .isEqualTo("false");
        assertThat(res1.getHeaders().getFirst("X-Idempotent-Replay"))
            .as("first request: X-Idempotent-Replay should be false")
            .isEqualTo("false");

        // Second POST — same event, no Idempotency-Key header
        var res2 = restTemplate.exchange(
            baseUrl() + "/v1/notifications",
            HttpMethod.POST,
            new HttpEntity<>(body, headers),
            String.class);

        assertThat(res2.getStatusCode().value())
            .as("second request (duplicate event) should be 200 OK")
            .isEqualTo(200);
        assertThat(res2.getHeaders().getFirst("X-Event-Duplicate"))
            .as("second request: X-Event-Duplicate should be true")
            .isEqualTo("true");
        assertThat(res2.getHeaders().getFirst("X-Idempotent-Replay"))
            .as("second request: X-Idempotent-Replay should be false (no header used)")
            .isEqualTo("false");
    }
}
