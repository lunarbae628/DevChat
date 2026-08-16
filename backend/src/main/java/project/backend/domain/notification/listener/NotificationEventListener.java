package project.backend.domain.notification.listener;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import java.util.concurrent.Executor;
import org.springframework.core.task.TaskRejectedException;
import project.backend.domain.notification.dto.NotificationDto;

@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationEventListener {

    private final SimpMessagingTemplate simpMessagingTemplate;
    @Qualifier("notificationDeliveryExecutor")
    private final Executor notificationDeliveryExecutor;
    @Qualifier("notificationAlertExecutor")
    private final Executor notificationAlertExecutor;
    private final MeterRegistry meterRegistry;
    private final DiscordNotificationDeliveryAlert discordNotificationDeliveryAlert;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleNotification(NotificationDto event) {
        try {
            notificationDeliveryExecutor.execute(() -> deliver(event));
        } catch (TaskRejectedException exception) {
            reportFailure(event, "rejected", exception);
        }
    }

    private void deliver(NotificationDto event) {
        try {
            simpMessagingTemplate.convertAndSend("/topic/notifications/" + event.receiverUsername(),
                event);
            meterRegistry.counter("notification.delivery", "result", "success", "type",
                event.type().name()).increment();
        } catch (RuntimeException exception) {
            reportFailure(event, "failure", exception);
        }
    }

    private void reportFailure(NotificationDto event, String result, RuntimeException exception) {
        meterRegistry.counter("notification.delivery", "result", result, "type",
            event.type().name()).increment();
        log.warn("notification delivery failed: type={}, stage={}, exception={}", event.type(), result,
            exception.getClass().getSimpleName());
        try {
            notificationAlertExecutor.execute(
                () -> discordNotificationDeliveryAlert.send(event.type(), result, exception));
        } catch (TaskRejectedException alertException) {
            meterRegistry.counter("notification.alert", "result", "rejected", "type",
                event.type().name()).increment();
            log.warn("notification delivery Discord alert rejected: type={}", event.type());
        }
    }

}
