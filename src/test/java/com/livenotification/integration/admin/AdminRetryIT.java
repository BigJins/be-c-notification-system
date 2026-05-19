package com.livenotification.integration.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.livenotification.admin.application.AdminRetryService;
import com.livenotification.delivery.domain.ChannelType;
import com.livenotification.integration.support.AbstractIntegrationTest;
import com.livenotification.integration.support.TestNotificationFixtures;
import com.livenotification.notification.domain.NotificationId;
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
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the admin-retry endpoint resurrects a DEAD delivery:
 * - delivery.state  → PENDING
 * - delivery.attempt_count preserved (monotone invariant)
 * - at least one new delivery_attempt row in state READY
 */
class AdminRetryIT extends AbstractIntegrationTest {

    @Autowired TestRestTemplate restTemplate;
    @Autowired ObjectMapper objectMapper;
    @Autowired AdminRetryService adminRetryService;

    /** Admin token default value from application.yml (no override in test env). */
    private static final String ADMIN_TOKEN = "dev-token-do-not-use-in-prod";

    @Test
    void adminRetry_resurrectsDeadDelivery_preservesAttemptCount() throws Exception {
        // Step 1: register a transient-failure notification → will eventually become DEAD
        String body = objectMapper.writeValueAsString(TestNotificationFixtures.registerBody(
            "admin-e1", "u-admin", NotificationType.PAYMENT_CONFIRMED,
            List.of(ChannelType.EMAIL),
            TestNotificationFixtures.payloadWithTransientFailure(objectMapper)));

        HttpHeaders postH = new HttpHeaders();
        postH.setContentType(MediaType.APPLICATION_JSON);
        var reg = restTemplate.exchange(
            baseUrl() + "/v1/notifications",
            HttpMethod.POST,
            new HttpEntity<>(body, postH),
            Map.class);
        assertThat(reg.getStatusCode().value()).isEqualTo(202);
        UUID notificationId = UUID.fromString((String) reg.getBody().get("id"));

        // Step 2: wait until the delivery reaches DEAD (max-attempts=3, interval~200ms each)
        Awaitility.await()
            .atMost(Duration.ofSeconds(15))
            .pollInterval(Duration.ofMillis(200))
            .untilAsserted(() -> {
                String state = jdbcTemplate.queryForObject(
                    "SELECT state FROM delivery WHERE notification_id = ? AND channel = 'EMAIL'",
                    String.class, notificationId);
                assertThat(state).isEqualTo("DEAD");
            });

        int preRetryAttemptCount = jdbcTemplate.queryForObject(
            "SELECT attempt_count FROM delivery WHERE notification_id = ? AND channel = 'EMAIL'",
            Integer.class, notificationId);
        int preRetryAttemptRows = jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM delivery_attempt da
            JOIN delivery d ON d.id = da.delivery_id
            WHERE d.notification_id = ?
            """, Integer.class, notificationId);

        // Step 3: admin retry — POST /v1/admin/notifications/{id}/retry
        HttpHeaders adminH = new HttpHeaders();
        adminH.set("X-Admin-Token", ADMIN_TOKEN);
        var retryRes = restTemplate.exchange(
            baseUrl() + "/v1/admin/notifications/" + notificationId + "/retry",
            HttpMethod.POST,
            new HttpEntity<>(adminH),
            Void.class);
        assertThat(retryRes.getStatusCode().value()).isEqualTo(204);

        // Step 4: assert PENDING + attempt_count preserved + new READY attempt row
        Map<String, Object> delivery = jdbcTemplate.queryForMap(
            "SELECT state, attempt_count FROM delivery WHERE notification_id = ? AND channel = 'EMAIL'",
            notificationId);
        assertThat(delivery.get("state")).isEqualTo("PENDING");
        assertThat(((Number) delivery.get("attempt_count")).intValue())
            .as("attempt_count must be preserved (monotone invariant) after admin retry")
            .isEqualTo(preRetryAttemptCount);

        Integer totalAttemptRows = jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM delivery_attempt da
            JOIN delivery d ON d.id = da.delivery_id
            WHERE d.notification_id = ?
            """, Integer.class, notificationId);
        Integer activeAttemptRows = jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM delivery_attempt da
            JOIN delivery d ON d.id = da.delivery_id
            WHERE d.notification_id = ? AND da.state IN ('READY', 'IN_PROGRESS', 'DONE')
            """, Integer.class, notificationId);
        assertThat(totalAttemptRows)
            .as("admin retry must append exactly one new delivery_attempt row")
            .isEqualTo(preRetryAttemptRows + 1);
        assertThat(activeAttemptRows)
            .as("the retried attempt may already be consumed by the worker, but must exist")
            .isGreaterThanOrEqualTo(1);
    }

    @Test
    void adminRetry_requiresAdminToken() {
        var retryRes = restTemplate.exchange(
            baseUrl() + "/v1/admin/notifications/" + UUID.randomUUID() + "/retry",
            HttpMethod.POST,
            HttpEntity.EMPTY,
            String.class);

        assertThat(retryRes.getStatusCode().value()).isEqualTo(401);
        assertThat(retryRes.getHeaders().getContentType().toString()).startsWith("application/problem+json");
        assertThat(retryRes.getBody()).contains("missing or invalid X-Admin-Token");
    }

    @Test
    void adminRetry_rejectsInvalidAdminToken() {
        HttpHeaders adminH = new HttpHeaders();
        adminH.set("X-Admin-Token", "wrong-token");

        var retryRes = restTemplate.exchange(
            baseUrl() + "/v1/admin/notifications/" + UUID.randomUUID() + "/retry",
            HttpMethod.POST,
            new HttpEntity<>(adminH),
            String.class);

        assertThat(retryRes.getStatusCode().value()).isEqualTo(401);
        assertThat(retryRes.getHeaders().getContentType().toString()).startsWith("application/problem+json");
        assertThat(retryRes.getBody()).contains("missing or invalid X-Admin-Token");
    }

    @Test
    void adminRetry_isConcurrencySafe_createsSingleReadyAttempt() throws Exception {
        UUID notificationId = UUID.randomUUID();
        UUID deliveryId = UUID.randomUUID();
        Instant now = Instant.now();

        jdbcTemplate.update("""
            INSERT INTO notification (id, event_id, recipient_id, type, payload, created_at, updated_at)
            VALUES (?, 'admin-concurrent-e1', 'u1', 'PAYMENT_CONFIRMED', '{}'::jsonb, ?, ?)
            """, notificationId, java.sql.Timestamp.from(now), java.sql.Timestamp.from(now));

        jdbcTemplate.update("""
            INSERT INTO delivery (id, notification_id, channel, state, attempt_count, last_error, created_at, updated_at)
            VALUES (?, ?, 'EMAIL', 'DEAD', 3, 'boom', ?, ?)
            """, deliveryId, notificationId, java.sql.Timestamp.from(now), java.sql.Timestamp.from(now));

        CountDownLatch startGun = new CountDownLatch(1);
        Callable<Boolean> task = () -> {
            startGun.await();
            try {
                adminRetryService.retry(new NotificationId(notificationId));
                return true;
            } catch (Exception ignored) {
                return false;
            }
        };

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> a = pool.submit(task);
            Future<Boolean> b = pool.submit(task);
            startGun.countDown();

            boolean successA = a.get();
            boolean successB = b.get();

            Integer ready = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM delivery_attempt
                WHERE delivery_id = ? AND state = 'READY'
                """, Integer.class, deliveryId);
            String state = jdbcTemplate.queryForObject(
                "SELECT state FROM delivery WHERE id = ?",
                String.class, deliveryId);

            assertThat(successA || successB).isTrue();
            assertThat(ready).isEqualTo(1);
            assertThat(state).isEqualTo("PENDING");
        } finally {
            pool.shutdownNow();
        }
    }
}
