package project.backend.global.config.async;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class NotificationDeliveryPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class,
            ValidationAutoConfiguration.class))
        .withUserConfiguration(PropertiesConfiguration.class);

    @Test
    @DisplayName("알림 전달 executor와 Discord 경보 설정을 바인딩한다")
    void bindsNotificationDeliveryExecutorAndDiscordProperties() {
        contextRunner.withPropertyValues(
            "app.notification-delivery.core-pool-size=3",
            "app.notification-delivery.max-pool-size=6",
            "app.notification-delivery.queue-capacity=50",
            "app.notification-delivery.discord.webhook-url=https://discord.com/api/webhooks/test/token",
            "app.notification-delivery.discord.alert-cooldown=2m"
        ).run(context -> {
            NotificationDeliveryProperties properties = context.getBean(
                NotificationDeliveryProperties.class);

            assertThat(NotificationDeliveryProperties.class.isRecord()).isTrue();
            assertThat(properties.corePoolSize()).isEqualTo(3);
            assertThat(properties.maxPoolSize()).isEqualTo(6);
            assertThat(properties.queueCapacity()).isEqualTo(50);
            assertThat(properties.discord().webhookUrl())
                .isEqualTo("https://discord.com/api/webhooks/test/token");
            assertThat(properties.discord().alertCooldown()).isEqualTo(java.time.Duration.ofMinutes(2));
        });
    }

    @Test
    @DisplayName("알림 전달 executor 크기는 1 이상이어야 한다")
    void rejectsNonPositiveExecutorSize() {
        contextRunner.withPropertyValues(
            "app.notification-delivery.core-pool-size=0",
            "app.notification-delivery.max-pool-size=6",
            "app.notification-delivery.queue-capacity=50",
            "app.notification-delivery.discord.webhook-url=",
            "app.notification-delivery.discord.alert-cooldown=2m"
        ).run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("알림 전달 최대 스레드 수는 기본 스레드 수보다 작을 수 없다")
    void rejectsMaxPoolSizeSmallerThanCorePoolSize() {
        contextRunner.withPropertyValues(
            "app.notification-delivery.core-pool-size=3",
            "app.notification-delivery.max-pool-size=2",
            "app.notification-delivery.queue-capacity=50",
            "app.notification-delivery.discord.webhook-url=",
            "app.notification-delivery.discord.alert-cooldown=2m"
        ).run(context -> assertThat(context).hasFailed());
    }

    @Configuration
    @EnableConfigurationProperties(NotificationDeliveryProperties.class)
    static class PropertiesConfiguration {
    }
}
