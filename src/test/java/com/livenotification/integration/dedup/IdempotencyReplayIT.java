package com.livenotification.integration.dedup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.livenotification.delivery.domain.ChannelType;
import com.livenotification.integration.support.AbstractIntegrationTest;
import com.livenotification.integration.support.TestNotificationFixtures;
import com.livenotification.notification.domain.NotificationType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IdempotencyReplayIT extends AbstractIntegrationTest {

    @Autowired TestRestTemplate restTemplate;
    @Autowired ObjectMapper objectMapper;

    @Test
    @DisplayName("Case A: new event + new Idempotency-Key -> 202, both headers false")
    void caseA_newEvent_newIdempotencyKey() throws Exception {
        String body = objectMapper.writeValueAsString(TestNotificationFixtures.registerBody(
            "idem-event-A", "uA", NotificationType.PAYMENT_CONFIRMED,
            List.of(ChannelType.EMAIL),
            TestNotificationFixtures.payloadNormal(objectMapper)));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", "idem-key-A");

        var res = restTemplate.exchange(
            baseUrl() + "/v1/notifications",
            HttpMethod.POST,
            new HttpEntity<>(body, headers),
            String.class);

        assertThat(res.getStatusCode().value()).isEqualTo(202);
        assertThat(res.getHeaders().getFirst("X-Event-Duplicate")).isEqualTo("false");
        assertThat(res.getHeaders().getFirst("X-Idempotent-Replay")).isEqualTo("false");
    }

    @Test
    @DisplayName("Case B: duplicate event + new Idempotency-Key -> 200, event-dup=true, replay=false")
    void caseB_duplicateEvent_newIdempotencyKey() throws Exception {
        String body = objectMapper.writeValueAsString(TestNotificationFixtures.registerBody(
            "idem-event-B", "uB", NotificationType.PAYMENT_CONFIRMED,
            List.of(ChannelType.EMAIL),
            TestNotificationFixtures.payloadNormal(objectMapper)));

        HttpHeaders firstHeaders = new HttpHeaders();
        firstHeaders.setContentType(MediaType.APPLICATION_JSON);
        var first = restTemplate.exchange(
            baseUrl() + "/v1/notifications",
            HttpMethod.POST,
            new HttpEntity<>(body, firstHeaders),
            String.class);
        assertThat(first.getStatusCode().value()).isEqualTo(202);

        HttpHeaders replayHeaders = new HttpHeaders();
        replayHeaders.setContentType(MediaType.APPLICATION_JSON);
        replayHeaders.set("Idempotency-Key", "idem-key-B");
        var second = restTemplate.exchange(
            baseUrl() + "/v1/notifications",
            HttpMethod.POST,
            new HttpEntity<>(body, replayHeaders),
            String.class);

        assertThat(second.getStatusCode().value()).isEqualTo(200);
        assertThat(second.getHeaders().getFirst("X-Event-Duplicate")).isEqualTo("true");
        assertThat(second.getHeaders().getFirst("X-Idempotent-Replay")).isEqualTo("false");
    }

    @Test
    @DisplayName("Case C: new event + replayed Idempotency-Key -> 200, both headers true")
    void caseC_newEvent_replayedIdempotencyKey() throws Exception {
        String body = objectMapper.writeValueAsString(TestNotificationFixtures.registerBody(
            "idem-event-C", "uC", NotificationType.PAYMENT_CONFIRMED,
            List.of(ChannelType.EMAIL),
            TestNotificationFixtures.payloadNormal(objectMapper)));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", "idem-key-C");

        var first = restTemplate.exchange(
            baseUrl() + "/v1/notifications",
            HttpMethod.POST,
            new HttpEntity<>(body, headers),
            String.class);
        assertThat(first.getStatusCode().value()).isEqualTo(202);

        var second = restTemplate.exchange(
            baseUrl() + "/v1/notifications",
            HttpMethod.POST,
            new HttpEntity<>(body, headers),
            String.class);

        assertThat(second.getStatusCode().value()).isEqualTo(200);
        assertThat(second.getHeaders().getFirst("X-Event-Duplicate")).isEqualTo("true");
        assertThat(second.getHeaders().getFirst("X-Idempotent-Replay")).isEqualTo("true");
    }

    @Test
    @DisplayName("Case D: duplicate event + replayed Idempotency-Key -> 200, both headers true")
    void caseD_duplicateEvent_replayedIdempotencyKey() throws Exception {
        String body = objectMapper.writeValueAsString(TestNotificationFixtures.registerBody(
            "idem-event-D", "uD", NotificationType.PAYMENT_CONFIRMED,
            List.of(ChannelType.EMAIL),
            TestNotificationFixtures.payloadNormal(objectMapper)));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", "idem-key-D");

        var first = restTemplate.exchange(
            baseUrl() + "/v1/notifications",
            HttpMethod.POST,
            new HttpEntity<>(body, headers),
            String.class);
        assertThat(first.getStatusCode().value()).isEqualTo(202);

        var second = restTemplate.exchange(
            baseUrl() + "/v1/notifications",
            HttpMethod.POST,
            new HttpEntity<>(body, headers),
            String.class);

        assertThat(second.getStatusCode().value()).isEqualTo(200);
        assertThat(second.getHeaders().getFirst("X-Event-Duplicate")).isEqualTo("true");
        assertThat(second.getHeaders().getFirst("X-Idempotent-Replay")).isEqualTo("true");
    }
}
