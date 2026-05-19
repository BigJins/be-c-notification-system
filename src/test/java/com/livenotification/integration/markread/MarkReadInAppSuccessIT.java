package com.livenotification.integration.markread;

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

import static org.assertj.core.api.Assertions.assertThat;

class MarkReadInAppSuccessIT extends AbstractIntegrationTest {

    @Autowired TestRestTemplate restTemplate;
    @Autowired ObjectMapper objectMapper;

    @Test
    void markRead_succeedsWhenInAppDeliveryIsSent() throws Exception {
        String body = objectMapper.writeValueAsString(TestNotificationFixtures.registerBody(
            "e-inapp-ok", "u1", NotificationType.PAYMENT_CONFIRMED,
            List.of(ChannelType.IN_APP),
            TestNotificationFixtures.payloadNormal(objectMapper)));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        var register = restTemplate.exchange(
            baseUrl() + "/v1/notifications",
            HttpMethod.POST,
            new HttpEntity<>(body, headers),
            Map.class);

        assertThat(register.getStatusCode().value()).isEqualTo(202);
        String notificationId = (String) register.getBody().get("id");

        var patch = restTemplate.exchange(
            baseUrl() + "/v1/notifications/" + notificationId + "/read",
            HttpMethod.PATCH,
            HttpEntity.EMPTY,
            String.class);

        assertThat(patch.getStatusCode().value()).isEqualTo(204);

        var detail = restTemplate.getForEntity(
            baseUrl() + "/v1/notifications/" + notificationId,
            Map.class);

        assertThat(detail.getStatusCode().value()).isEqualTo(200);
        assertThat(detail.getBody().get("readAt")).isNotNull();
    }
}
