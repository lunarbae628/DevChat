package project.backend.domain.notification.listener;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import project.backend.domain.notification.dto.NotificationDto;
import project.backend.domain.notification.entity.NotificationType;

class NotificationEventListenerTest {

    @Test
    @DisplayName("WebSocket 전송 실패는 커밋 후 알림 처리자 밖으로 전파되지 않는다")
    void handleFriendRequest_whenWebSocketDeliveryFails_doesNotPropagate() {
        SimpMessagingTemplate messagingTemplate = org.mockito.Mockito.mock(SimpMessagingTemplate.class);
        DiscordNotificationDeliveryAlert discordAlert = org.mockito.Mockito.mock(
            DiscordNotificationDeliveryAlert.class);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        NotificationEventListener listener = new NotificationEventListener(messagingTemplate,
            Runnable::run, Runnable::run, meterRegistry, discordAlert);
        NotificationDto event = new NotificationDto(
            1L, false, NotificationType.FRIEND_REQUESTED, "receiver", "sender", "보낸이",
            "profile.png", "친구 요청", 2L, java.time.LocalDateTime.now());
        doThrow(new MessageDeliveryException("broker unavailable"))
            .when(messagingTemplate).convertAndSend(any(String.class), any(Object.class));

        assertThatCode(() -> listener.handleNotification(event)).doesNotThrowAnyException();
        assertThat(meterRegistry.counter("notification.delivery", "result", "failure", "type",
            "FRIEND_REQUESTED").count()).isEqualTo(1.0);
        verify(discordAlert).send(eq(NotificationType.FRIEND_REQUESTED), eq("failure"),
            org.mockito.ArgumentMatchers.any(MessageDeliveryException.class));
    }

    @Test
    @DisplayName("전용 executor 거절은 WebSocket 전송 없이 실패로 관측한다")
    void handleNotification_whenExecutorRejects_recordsRejectedDelivery() {
        SimpMessagingTemplate messagingTemplate = org.mockito.Mockito.mock(SimpMessagingTemplate.class);
        DiscordNotificationDeliveryAlert discordAlert = org.mockito.Mockito.mock(
            DiscordNotificationDeliveryAlert.class);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        NotificationEventListener listener = new NotificationEventListener(messagingTemplate,
            task -> {
                throw new TaskRejectedException("queue full");
            }, Runnable::run, meterRegistry, discordAlert);
        NotificationDto event = new NotificationDto(
            1L, false, NotificationType.STUDY_APPLY, "receiver", "sender", "보낸이",
            "profile.png", "스터디 신청", 2L, java.time.LocalDateTime.now());

        assertThatCode(() -> listener.handleNotification(event)).doesNotThrowAnyException();

        assertThat(meterRegistry.counter("notification.delivery", "result", "rejected", "type",
            "STUDY_APPLY").count()).isEqualTo(1.0);
        verify(discordAlert).send(eq(NotificationType.STUDY_APPLY), eq("rejected"),
            org.mockito.ArgumentMatchers.any(TaskRejectedException.class));
    }

    @Test
    @DisplayName("전달 큐 거절의 Discord 경보는 요청 스레드에서 HTTP 호출하지 않는다")
    void handleNotification_whenExecutorRejects_defersDiscordAlertToAlertExecutor() {
        SimpMessagingTemplate messagingTemplate = org.mockito.Mockito.mock(SimpMessagingTemplate.class);
        DiscordNotificationDeliveryAlert discordAlert = org.mockito.Mockito.mock(
            DiscordNotificationDeliveryAlert.class);
        List<Runnable> alertTasks = new ArrayList<>();
        NotificationEventListener listener = new NotificationEventListener(messagingTemplate,
            task -> {
                throw new TaskRejectedException("queue full");
            }, alertTasks::add, new SimpleMeterRegistry(), discordAlert);
        NotificationDto event = new NotificationDto(
            1L, false, NotificationType.FRIEND_ACCEPTED, "receiver", "sender", "보낸이",
            "profile.png", "친구 수락", 2L, java.time.LocalDateTime.now());

        listener.handleNotification(event);

        verifyNoInteractions(discordAlert);
        assertThat(alertTasks).hasSize(1);
        alertTasks.getFirst().run();
        verify(discordAlert).send(eq(NotificationType.FRIEND_ACCEPTED), eq("rejected"),
            org.mockito.ArgumentMatchers.any(TaskRejectedException.class));
    }

    @Test
    @DisplayName("Discord 경보 executor 거절은 metric으로만 기록하고 전달 실패를 다시 전파하지 않는다")
    void handleNotification_whenAlertExecutorRejects_recordsAlertRejection() {
        SimpMessagingTemplate messagingTemplate = org.mockito.Mockito.mock(SimpMessagingTemplate.class);
        DiscordNotificationDeliveryAlert discordAlert = org.mockito.Mockito.mock(
            DiscordNotificationDeliveryAlert.class);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        NotificationEventListener listener = new NotificationEventListener(messagingTemplate,
            task -> {
                throw new TaskRejectedException("delivery queue full");
            }, task -> {
                throw new TaskRejectedException("alert queue full");
            }, meterRegistry, discordAlert);
        NotificationDto event = new NotificationDto(
            1L, false, NotificationType.STUDY_REJECTED, "receiver", "sender", "보낸이",
            "profile.png", "스터디 거절", 2L, java.time.LocalDateTime.now());

        assertThatCode(() -> listener.handleNotification(event)).doesNotThrowAnyException();

        assertThat(meterRegistry.counter("notification.alert", "result", "rejected", "type",
            "STUDY_REJECTED").count()).isEqualTo(1.0);
        verifyNoInteractions(discordAlert);
    }
}
