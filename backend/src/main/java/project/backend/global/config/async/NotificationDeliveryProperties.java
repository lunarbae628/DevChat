package project.backend.global.config.async;

import java.time.Duration;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.notification-delivery")
public record NotificationDeliveryProperties(
    @NotNull @Min(1) Integer corePoolSize,
    @NotNull @Min(1) Integer maxPoolSize,
    @NotNull @Min(1) Integer queueCapacity,
    @NotNull @Valid Discord discord
) {

    @AssertTrue(message = "max-pool-size must be greater than or equal to core-pool-size")
    public boolean isPoolSizeRangeValid() {
        return corePoolSize == null || maxPoolSize == null || maxPoolSize >= corePoolSize;
    }

    public record Discord(
        @NotNull String webhookUrl,
        @NotNull @DurationMin(millis = 1) Duration alertCooldown
    ) {
    }
}
