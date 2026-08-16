package project.backend.domain.notification.listener;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import project.backend.domain.notification.entity.NotificationType;
import project.backend.global.config.async.NotificationDeliveryProperties;

@Slf4j
@Component
public class DiscordNotificationDeliveryAlert {

    private final NotificationDeliveryProperties properties;
    private final RestClient restClient;
    private final Clock clock;
    private final AtomicLong lastAlertAtMillis = new AtomicLong(Long.MIN_VALUE);

    @Autowired
    public DiscordNotificationDeliveryAlert(NotificationDeliveryProperties properties) {
        this(properties, createRestClient(), Clock.systemUTC());
    }

    DiscordNotificationDeliveryAlert(NotificationDeliveryProperties properties, RestClient restClient,
        Clock clock) {
        this.properties = properties;
        this.restClient = restClient;
        this.clock = clock;
    }

    public void send(NotificationType type, String stage, Throwable exception) {
        if (properties.discord().webhookUrl().isBlank() || !acquireAlertSlot()) {
            return;
        }

        try {
            restClient.post()
                .uri(properties.discord().webhookUrl())
                .body(Map.of(
                    "content", "notification delivery failure: type=%s stage=%s exception=%s at=%s"
                        .formatted(type, stage, exception.getClass().getSimpleName(), Instant.now(clock))
                ))
                .retrieve()
                .toBodilessEntity();
        } catch (RuntimeException alertException) {
            log.warn("notification delivery Discord alert failed: exception={}",
                alertException.getClass().getSimpleName());
        }
    }

    private boolean acquireAlertSlot() {
        long now = clock.millis();
        long cooldownMillis = properties.discord().alertCooldown().toMillis();
        long previous = lastAlertAtMillis.get();
        if (previous != Long.MIN_VALUE && now - previous < cooldownMillis) {
            return false;
        }
        return lastAlertAtMillis.compareAndSet(previous, now);
    }

    private static RestClient createRestClient() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(2));
        requestFactory.setReadTimeout(Duration.ofSeconds(2));
        return RestClient.builder().requestFactory(requestFactory).build();
    }
}
