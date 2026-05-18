package com.livenotification.integration.tier3;

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
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tier 3 explicit verification of the 4-case header independence matrix:
 * <pre>
 *   Case A: new event  + new key       → 202, event-dup=false, replay=false
 *   Case B: dup event  + new key       → 200, event-dup=true,  replay=false
 *   Case C: new event  + replayed key  → 200, event-dup=true,  replay=true
 *   Case D: dup event  + replayed key  → 200, event-dup=true,  replay=true
 * </pre>
 * X-Event-Duplicate and X-Idempotent-Replay are independent header dimensions.
 */
class HeaderAndEventCompositionIT extends AbstractIntegrationTest {

    @Autowired TestRestTemplate restTemplate;
    @Autowired ObjectMapper objectMapper;

    @Test
    void all4HeaderCases_independent() throws Exception {

        // ── Case A: new event + new key → 202, both headers false ────────────
        String bodyA = buildBody("hec-A", "u-hec-A");
        ResponseEntity<Map> resA = post(bodyA, "key-hec-A");
        assertThat(resA.getStatusCode().value()).isEqualTo(202);
        assertThat(resA.getHeaders().getFirst("X-Event-Duplicate")).isEqualTo("false");
        assertThat(resA.getHeaders().getFirst("X-Idempotent-Replay")).isEqualTo("false");

        // ── Case B: duplicate event + new key → 200, event-dup=true, replay=false ─
        String bodyB = buildBody("hec-B", "u-hec-B");
        ResponseEntity<Map> resB1 = post(bodyB, null);          // first submission, no key
        assertThat(resB1.getStatusCode().value()).isEqualTo(202);
        ResponseEntity<Map> resB2 = post(bodyB, "key-hec-B");   // same event, new key
        assertThat(resB2.getStatusCode().value()).isEqualTo(200);
        assertThat(resB2.getHeaders().getFirst("X-Event-Duplicate")).isEqualTo("true");
        assertThat(resB2.getHeaders().getFirst("X-Idempotent-Replay")).isEqualTo("false");

        // ── Case C: new event + replayed key → 200, both true ────────────────
        String bodyC = buildBody("hec-C", "u-hec-C");
        ResponseEntity<Map> resC1 = post(bodyC, "key-hec-C");   // first submission
        assertThat(resC1.getStatusCode().value()).isEqualTo(202);
        ResponseEntity<Map> resC2 = post(bodyC, "key-hec-C");   // same event + same key
        assertThat(resC2.getStatusCode().value()).isEqualTo(200);
        assertThat(resC2.getHeaders().getFirst("X-Event-Duplicate")).isEqualTo("true");
        assertThat(resC2.getHeaders().getFirst("X-Idempotent-Replay")).isEqualTo("true");

        // ── Case D: duplicate event + replayed key → 200, both true ──────────
        // Re-use Case C's key to ensure duplicate event + replayed key produces same result
        String bodyD = buildBody("hec-D", "u-hec-D");
        ResponseEntity<Map> resD1 = post(bodyD, "key-hec-D");   // first submission
        assertThat(resD1.getStatusCode().value()).isEqualTo(202);
        ResponseEntity<Map> resD2 = post(bodyD, "key-hec-D");   // same body + same key again
        assertThat(resD2.getStatusCode().value()).isEqualTo(200);
        assertThat(resD2.getHeaders().getFirst("X-Event-Duplicate")).isEqualTo("true");
        assertThat(resD2.getHeaders().getFirst("X-Idempotent-Replay")).isEqualTo("true");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private String buildBody(String eventId, String recipientId) throws Exception {
        return objectMapper.writeValueAsString(TestNotificationFixtures.registerBody(
            eventId, recipientId,
            NotificationType.PAYMENT_CONFIRMED,
            List.of(ChannelType.EMAIL),
            TestNotificationFixtures.payloadNormal(objectMapper)));
    }

    private ResponseEntity<Map> post(String body, String idempotencyKey) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (idempotencyKey != null) {
            h.set("Idempotency-Key", idempotencyKey);
        }
        return restTemplate.exchange(baseUrl() + "/v1/notifications",
            HttpMethod.POST, new HttpEntity<>(body, h), Map.class);
    }
}
