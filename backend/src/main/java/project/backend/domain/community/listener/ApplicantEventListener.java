package project.backend.domain.community.listener;

import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import project.backend.domain.community.dto.event.ApplicantResultEvent;
import project.backend.domain.community.dto.event.ApplyEvent;
import project.backend.domain.member.entity.Member;
import project.backend.domain.notification.app.NotificationService;
import project.backend.domain.notification.dto.NotificationDto;
import project.backend.domain.notification.entity.Notification;
import project.backend.domain.notification.entity.NotificationType;

@Component
@RequiredArgsConstructor
public class ApplicantEventListener {

    private final NotificationService notificationService;
    private final ApplicationEventPublisher eventPublisher;

    @EventListener
    @Transactional
    public void handleApply(ApplyEvent event) {
        Member receiver = Member.builder().id(event.authorId()).build();
        Member sender = Member.builder().id(event.applicantId()).build();

        Notification saved = notificationService.saveNotification(
            Notification.ofStudyApply(receiver, sender, event.postId())
        );

        eventPublisher.publishEvent(NotificationDto.ofNotification(saved, event.authorUsername()));
    }

    @EventListener
    @Transactional
    public void handleApplicantResult(ApplicantResultEvent event) {
        Member receiver = Member.builder().id(event.applicantMemberId()).build();
        Member sender = Member.builder().id(event.authorId()).build();

        NotificationType type = event.approved()
            ? NotificationType.STUDY_APPROVED
            : NotificationType.STUDY_REJECTED;

        Notification saved = notificationService.saveNotification(
            Notification.ofStudyResult(receiver, sender, event.postId(), type)
        );

        eventPublisher.publishEvent(NotificationDto.ofNotification(saved, event.applicantUsername()));
    }
}
