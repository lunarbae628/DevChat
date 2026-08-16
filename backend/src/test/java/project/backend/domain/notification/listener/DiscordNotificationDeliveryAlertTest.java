package project.backend.domain.notification.listener;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import project.backend.domain.notification.entity.NotificationType;
import project.backend.global.config.async.NotificationDeliveryProperties;

class DiscordNotificationDeliveryAlertTest {

    @Test
    @DisplayName("Discord 경보는 민감 정보를 제외하고 쿨다운 동안 한 번만 전송한다")
    void sendsSanitizedDiscordContentOnlyOnceDuringCooldown() {
        NotificationDeliveryProperties properties = new NotificationDeliveryProperties(2, 4, 100,
            new NotificationDeliveryProperties.Discord(
                "https://discord.com/api/webhooks/test/token", Duration.ofMinutes(5)));
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        DiscordNotificationDeliveryAlert alert = new DiscordNotificationDeliveryAlert(properties,
            builder.build(), Clock.fixed(Instant.parse("2026-08-14T03:00:00Z"), ZoneOffset.UTC));
        RuntimeException failure = new RuntimeException("receiver@example.com secret notification body");

        server.expect(requestTo("https://discord.com/api/webhooks/test/token"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("\"content\"")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("STUDY_APPLY")))
            .andExpect(content().string(org.hamcrest.Matchers.not(
                org.hamcrest.Matchers.containsString("receiver@example.com"))))
            .andRespond(withSuccess());

        alert.send(NotificationType.STUDY_APPLY, "failure", failure);
        alert.send(NotificationType.STUDY_APPLY, "failure", failure);

        server.verify();
    }
}
