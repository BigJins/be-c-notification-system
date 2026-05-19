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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ConcurrentDedupIT extends AbstractIntegrationTest {

    @Autowired TestRestTemplate restTemplate;
    @Autowired ObjectMapper objectMapper;

    @Test
    void concurrentSameEvent_only1Accepted_othersReturnDuplicate() throws Exception {
        int n = 50;   // 50 is plenty; 100 may flake on slow CI
        ExecutorService pool = Executors.newFixedThreadPool(20);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(n);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger duplicates = new AtomicInteger();

        String body = objectMapper.writeValueAsString(TestNotificationFixtures.registerBody(
            "race-event-1", "u1", NotificationType.PAYMENT_CONFIRMED,
            List.of(ChannelType.EMAIL),
            TestNotificationFixtures.payloadNormal(objectMapper)));

        for (int i = 0; i < n; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    HttpHeaders h = new HttpHeaders();
                    h.setContentType(MediaType.APPLICATION_JSON);
                    var res = restTemplate.exchange(baseUrl() + "/v1/notifications",
                        HttpMethod.POST, new HttpEntity<>(body, h), String.class);
                    if (res.getStatusCode().value() == 202) accepted.incrementAndGet();
                    else if (res.getStatusCode().value() == 200) duplicates.incrementAndGet();
                } catch (Exception ignore) {
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        done.await(30, TimeUnit.SECONDS);
        pool.shutdownNow();

        assertThat(accepted.get()).as("exactly one 202 accepted").isEqualTo(1);
        assertThat(duplicates.get()).as("remaining are 200 duplicates").isEqualTo(n - 1);
    }
}
