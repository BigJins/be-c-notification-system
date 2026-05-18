package com.livenotification.integration.recovery;

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

/**
 * DB-state durability test (replaces Spring-context-restart which is impractical in unit tests).
 *
 * Semantic: JdbcTemplate bypasses the Hibernate persistence context entirely.
 * If the application process were restarted, the same raw SQL queries would
 * still return the same rows — proving the state is fully on-disk, not in an
 * in-memory cache.
 */
class DurabilityIT extends AbstractIntegrationTest {

    @Autowired TestRestTemplate restTemplate;
    @Autowired ObjectMapper objectMapper;

    @Test
    void registeredNotification_isDurableInDb() throws Exception {
        String body = objectMapper.writeValueAsString(TestNotificationFixtures.registerBody(
            "dur-e1", "u-dur", NotificationType.PAYMENT_CONFIRMED,
            List.of(ChannelType.EMAIL),
            TestNotificationFixtures.payloadNormal(objectMapper)));

        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        var res = restTemplate.exchange(
            baseUrl() + "/v1/notifications",
            HttpMethod.POST,
            new HttpEntity<>(body, h),
            Map.class);

        assertThat(res.getStatusCode().value()).isEqualTo(202);
        UUID notificationId = UUID.fromString((String) res.getBody().get("id"));

        // JdbcTemplate bypasses Hibernate persistence context — proves DB-state durability.
        // If the worker / app process were restarted, these same SQL queries would still
        // return the rows, confirming that state is fully committed to Postgres.
        Integer notifCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM notification WHERE id = ?",
            Integer.class, notificationId);

        Integer deliveryCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM delivery WHERE notification_id = ?",
            Integer.class, notificationId);

        Integer attemptCount = jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM delivery_attempt da
            JOIN delivery d ON d.id = da.delivery_id
            WHERE d.notification_id = ?
            """, Integer.class, notificationId);

        assertThat(notifCount).isEqualTo(1);
        assertThat(deliveryCount).isEqualTo(1);
        assertThat(attemptCount).isEqualTo(1);
    }
}
