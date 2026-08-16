package project.backend.domain.community.listener;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Assertions;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import project.backend.domain.community.dto.event.ApplyEvent;
import project.backend.domain.community.dto.event.ApplicantResultEvent;
import project.backend.domain.notification.app.NotificationService;
import project.backend.domain.notification.dto.NotificationDto;
import project.backend.domain.notification.entity.Notification;
import project.backend.domain.notification.entity.NotificationType;
import project.backend.domain.member.entity.Member;

class ApplicantEventListenerTest {

    @Test
    @DisplayName("스터디 신청 알림 이벤트는 이벤트의 방장 username을 수신자로 보존한다")
    void handleApply_preservesAuthorUsernameAsReceiver() {
        NotificationService notificationService = mock(NotificationService.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        ApplicantEventListener listener = new ApplicantEventListener(notificationService, eventPublisher);
        Notification saved = mock(Notification.class);
        Member receiver = mock(Member.class);
        Member sender = mock(Member.class);
        when(receiver.getUsername()).thenReturn(null);
        when(sender.getUsername()).thenReturn("applicant");
        when(sender.getNickname()).thenReturn("신청자");
        when(saved.getReceiver()).thenReturn(receiver);
        when(saved.getSender()).thenReturn(sender);
        when(saved.getType()).thenReturn(NotificationType.STUDY_APPLY);
        when(notificationService.saveNotification(any(Notification.class))).thenReturn(saved);

        listener.handleApply(new ApplyEvent(1L, "author", 2L, "applicant", 3L, "post"));

        ArgumentCaptor<NotificationDto> notificationCaptor = ArgumentCaptor.forClass(NotificationDto.class);
        verify(eventPublisher).publishEvent(notificationCaptor.capture());
        Assertions.assertEquals("author", notificationCaptor.getValue().receiverUsername());
    }

    @Test
    @DisplayName("스터디 신청 결과 알림 이벤트는 이벤트의 신청자 username을 수신자로 보존한다")
    void handleApplicantResult_preservesApplicantUsernameAsReceiver() {
        NotificationService notificationService = mock(NotificationService.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        ApplicantEventListener listener = new ApplicantEventListener(notificationService, eventPublisher);
        Notification saved = mock(Notification.class);
        Member receiver = mock(Member.class);
        Member sender = mock(Member.class);
        when(receiver.getUsername()).thenReturn(null);
        when(sender.getUsername()).thenReturn("author");
        when(sender.getNickname()).thenReturn("방장");
        when(saved.getReceiver()).thenReturn(receiver);
        when(saved.getSender()).thenReturn(sender);
        when(saved.getType()).thenReturn(NotificationType.STUDY_APPROVED);
        when(notificationService.saveNotification(any(Notification.class))).thenReturn(saved);

        listener.handleApplicantResult(
            new ApplicantResultEvent(2L, "applicant", 1L, 3L, "post", true));

        ArgumentCaptor<NotificationDto> notificationCaptor = ArgumentCaptor.forClass(NotificationDto.class);
        verify(eventPublisher).publishEvent(notificationCaptor.capture());
        Assertions.assertEquals("applicant", notificationCaptor.getValue().receiverUsername());
    }
}
