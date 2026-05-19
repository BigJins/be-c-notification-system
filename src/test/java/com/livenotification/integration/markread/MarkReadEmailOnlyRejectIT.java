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

class MarkReadEmailOnlyRejectIT extends AbstractIntegrationTest {

    @Autowired TestRestTemplate restTemplate;
    @Autowired ObjectMapper objectMapper;

    @Test
    void markRead_rejectedWhenNoInAppSent() throws Exception {
        // 1. Register EMAIL-only notification
        String body = objectMapper.writeValueAsString(TestNotificationFixtures.registerBody(
            "e-email-only", "u1", NotificationType.PAYMENT_CONFIRMED,
            List.of(ChannelType.EMAIL),
            TestNotificationFixtures.payloadNormal(objectMapper)));

        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);

        var resReg = restTemplate.exchange(
            baseUrl() + "/v1/notifications",
            HttpMethod.POST,
            new HttpEntity<>(body, h),
            Map.class);

        assertThat(resReg.getStatusCode().value())
            .as("notification registration should succeed with 202")
            .isEqualTo(202);

        String notificationId = (String) resReg.getBody().get("id");
        assertThat(notificationId).as("response body must contain 'id' field").isNotNull();

        // 2. PATCH /read → 422 because no IN_APP delivery is SENT
        var resPatch = restTemplate.exchange(
            baseUrl() + "/v1/notifications/" + notificationId + "/read",
            HttpMethod.PATCH,
            HttpEntity.EMPTY,
            String.class);

        assertThat(resPatch.getStatusCode().value())
            .as("markRead with no IN_APP SENT should return 422 Unprocessable Entity")
            .isEqualTo(422);
        assertThat(resPatch.getBody())
            .as("ProblemDetail type should contain read-state-violation slug")
            .contains("read-state-violation");
    }
}
