package com.livenotification.integration.cleanup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.livenotification.delivery.domain.ChannelType;
import com.livenotification.idempotency.adapter.in.scheduler.IdempotencyCleanupWorker;
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

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class IdempotencyExpiryIT extends AbstractIntegrationTest {

    @Autowired TestRestTemplate restTemplate;
    @Autowired ObjectMapper objectMapper;
    @Autowired IdempotencyCleanupWorker cleanupWorker;

    @Test
    void expiredKey_isReusableBeforeCleanup_andRecordIsRefreshed() throws Exception {
        Instant oldCreated = Instant.now().minusSeconds(2 * 24 * 60 * 60L);
        Instant oldExpires = Instant.now().minusSeconds(24 * 60 * 60L);
        jdbcTemplate.update("""
            INSERT INTO idempotency_record (idempotency_key, request_hash, target_id, created_at, expires_at)
            VALUES ('expired-key-1', 'oldhash', ?, ?, ?)
            """, UUID.randomUUID(), Timestamp.from(oldCreated), Timestamp.from(oldExpires));

        String body = objectMapper.writeValueAsString(TestNotificationFixtures.registerBody(
            "expired-event-1", "u-exp", NotificationType.PAYMENT_CONFIRMED,
            List.of(ChannelType.EMAIL),
            TestNotificationFixtures.payloadNormal(objectMapper)));
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("Idempotency-Key", "expired-key-1");

        var res = restTemplate.exchange(baseUrl() + "/v1/notifications",
            HttpMethod.POST, new HttpEntity<>(body, h), Map.class);

        assertThat(res.getStatusCode().value()).isEqualTo(202);
        assertThat(res.getHeaders().getFirst("X-Idempotent-Replay")).isEqualTo("false");

        UUID notificationId = UUID.fromString((String) res.getBody().get("id"));
        Map<String, Object> record = jdbcTemplate.queryForMap("""
            SELECT request_hash, target_id, expires_at
            FROM idempotency_record
            WHERE idempotency_key = 'expired-key-1'
            """);
        assertThat(record.get("request_hash")).isNotEqualTo("oldhash");
        assertThat(record.get("target_id")).isEqualTo(notificationId);
        assertThat(((java.sql.Timestamp) record.get("expires_at")).toInstant()).isAfter(Instant.now());
    }

    @Test
    void cleanup_deletesExpiredRows_only() {
        Instant expiredCreated = Instant.now().minusSeconds(2 * 24 * 60 * 60L);
        Instant expiredAt = Instant.now().minusSeconds(60);
        Instant activeCreated = Instant.now().minusSeconds(60);
        Instant activeExpires = Instant.now().plusSeconds(24 * 60 * 60L);

        jdbcTemplate.update("""
            INSERT INTO idempotency_record (idempotency_key, request_hash, target_id, created_at, expires_at)
            VALUES ('expired-key-2', 'hash1', ?, ?, ?)
            """, UUID.randomUUID(), Timestamp.from(expiredCreated), Timestamp.from(expiredAt));
        jdbcTemplate.update("""
            INSERT INTO idempotency_record (idempotency_key, request_hash, target_id, created_at, expires_at)
            VALUES ('active-key-1', 'hash2', ?, ?, ?)
            """, UUID.randomUUID(), Timestamp.from(activeCreated), Timestamp.from(activeExpires));

        cleanupWorker.cleanup();

        assertThat(countIdempotency("expired-key-2")).isZero();
        assertThat(countIdempotency("active-key-1")).isEqualTo(1);
    }

    private int countIdempotency(String key) {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM idempotency_record WHERE idempotency_key = ?",
            Integer.class, key);
        return count == null ? 0 : count;
    }
}
