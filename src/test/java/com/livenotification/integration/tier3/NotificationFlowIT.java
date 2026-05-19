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
 * End-to-end representative user scenario covering the full notification lifecycle:
 * <ol>
 *   <li>POST channels=[EMAIL, IN_APP] → 202, both headers false</li>
 *   <li>Immediate GET → IN_APP already SENT</li>
 *   <li>Wait → EMAIL also SENT via worker</li>
 *   <li>Re-POST same eventId → 200, X-Event-Duplicate=true</li>
 *   <li>New eventId + Idempotency-Key twice → first 202, second 200 + X-Idempotent-Replay=true</li>
 * </ol>
 */
class NotificationFlowIT extends AbstractIntegrationTest {

    @Autowired TestRestTemplate restTemplate;
    @Autowired ObjectMapper objectMapper;

    @Test
    void representativeUserScenario_endToEnd() throws Exception {

        // ── Step 1: POST channels=[EMAIL, IN_APP] ─────────────────────────────
        String body1 = objectMapper.writeValueAsString(TestNotificationFixtures.registerBody(
            "flow-1", "u-flow", NotificationType.PAYMENT_CONFIRMED,
            List.of(ChannelType.EMAIL, ChannelType.IN_APP),
            TestNotificationFixtures.payloadNormal(objectMapper)));
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        var res1 = restTemplate.exchange(baseUrl() + "/v1/notifications",
            HttpMethod.POST, new HttpEntity<>(body1, h), Map.class);

        assertThat(res1.getStatusCode().value()).isEqualTo(202);
        assertThat(res1.getHeaders().getFirst("X-Event-Duplicate")).isEqualTo("false");
        assertThat(res1.getHeaders().getFirst("X-Idempotent-Replay")).isEqualTo("false");
        UUID id = UUID.fromString((String) res1.getBody().get("id"));

        // ── Step 2: Immediate GET — IN_APP already SENT ───────────────────────
        @SuppressWarnings("unchecked")
        Map<String, Object> getBody1 = restTemplate.exchange(
            baseUrl() + "/v1/notifications/" + id,
            HttpMethod.GET, HttpEntity.EMPTY, Map.class).getBody();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> deliveries1 = (List<Map<String, Object>>) getBody1.get("deliveries");
        Map<String, Object> inApp = deliveries1.stream()
            .filter(d -> "IN_APP".equals(d.get("channel")))
            .findFirst()
            .orElseThrow(() -> new AssertionError("No IN_APP delivery found"));
        assertThat(inApp.get("state"))
            .as("IN_APP delivery must be SENT immediately after POST")
            .isEqualTo("SENT");

        // ── Step 3: Wait for EMAIL to become SENT via worker ──────────────────
        Awaitility.await()
            .atMost(Duration.ofSeconds(3))
            .pollInterval(Duration.ofMillis(150))
            .untilAsserted(() -> {
                @SuppressWarnings("unchecked")
                Map<String, Object> bodyN = restTemplate.exchange(
                    baseUrl() + "/v1/notifications/" + id,
                    HttpMethod.GET, HttpEntity.EMPTY, Map.class).getBody();
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> deliveriesN = (List<Map<String, Object>>) bodyN.get("deliveries");
                Map<String, Object> email = deliveriesN.stream()
                    .filter(d -> "EMAIL".equals(d.get("channel")))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("No EMAIL delivery found"));
                assertThat(email.get("state"))
                    .as("EMAIL delivery must reach SENT via worker")
                    .isEqualTo("SENT");
            });

        // ── Step 4: Re-POST same eventId → 200, X-Event-Duplicate=true ────────
        var res2 = restTemplate.exchange(baseUrl() + "/v1/notifications",
            HttpMethod.POST, new HttpEntity<>(body1, h), Map.class);
        assertThat(res2.getStatusCode().value()).isEqualTo(200);
        assertThat(res2.getHeaders().getFirst("X-Event-Duplicate")).isEqualTo("true");
        assertThat(res2.getHeaders().getFirst("X-Idempotent-Replay")).isEqualTo("false");

        // ── Step 5: New event + Idempotency-Key twice → second is idempotent replay
        String body3 = objectMapper.writeValueAsString(TestNotificationFixtures.registerBody(
            "flow-2", "u-flow", NotificationType.PAYMENT_CONFIRMED,
            List.of(ChannelType.EMAIL),
            TestNotificationFixtures.payloadNormal(objectMapper)));
        HttpHeaders ih = new HttpHeaders();
        ih.setContentType(MediaType.APPLICATION_JSON);
        ih.set("Idempotency-Key", "flow-key-1");

        var res3 = restTemplate.exchange(baseUrl() + "/v1/notifications",
            HttpMethod.POST, new HttpEntity<>(body3, ih), Map.class);
        assertThat(res3.getStatusCode().value()).isEqualTo(202);
        assertThat(res3.getHeaders().getFirst("X-Idempotent-Replay")).isEqualTo("false");

        var res4 = restTemplate.exchange(baseUrl() + "/v1/notifications",
            HttpMethod.POST, new HttpEntity<>(body3, ih), Map.class);
        assertThat(res4.getStatusCode().value()).isEqualTo(200);
        // ADR-0002: replay supersedes event-dup — X-Event-Duplicate suppressed when replay=true.
        assertThat(res4.getHeaders().getFirst("X-Event-Duplicate")).isEqualTo("false");
        assertThat(res4.getHeaders().getFirst("X-Idempotent-Replay")).isEqualTo("true");
    }
}
