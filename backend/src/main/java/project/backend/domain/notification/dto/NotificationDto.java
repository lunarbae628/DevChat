package project.backend.domain.notification.dto;

import java.time.LocalDateTime;
import project.backend.domain.dm.dmMessage.entity.DmMessage;
import project.backend.domain.notification.entity.Notification;
import project.backend.domain.notification.entity.NotificationType;

public record NotificationDto(
    Long notificationId,
    Boolean isRead,
    NotificationType type,
    String receiverUsername,
    String senderUsername,
    String senderNickname,
    String senderImg,
    String content,
    Long referenceId,
    LocalDateTime createdAt
) {

    // Notification 객체로부터 생성
    public static NotificationDto ofNotification(Notification notification) {
        return ofNotification(notification, notification.getReceiver().getUsername());
    }

    public static NotificationDto ofNotification(Notification notification, String receiverUsername) {
        return ofNotification(notification, receiverUsername, notification.getSender().getNickname());
    }

    public static NotificationDto ofNotification(Notification notification, String receiverUsername,
        String senderNickname) {
        return new NotificationDto(
            notification.getId(),
            notification.isRead(),
            notification.getType(),
            receiverUsername,
            notification.getSender().getUsername(),
            senderNickname,
            notification.getSender().getProfileImage(),
            getContentByType(senderNickname, notification.getType()),
            notification.getReferenceId(),
            notification.getCreatedAt()
        );
    }

    public static NotificationDto ofDmMessage(DmMessage dmMessage, String receiverUsername) {
        return new NotificationDto(
            null,
            null,
            NotificationType.NEW_DM,
            receiverUsername,
            dmMessage.getSender().getUsername(),
            dmMessage.getSender().getNickname(),
            dmMessage.getSender().getProfileImage(),
            dmMessage.getContent(),
            dmMessage.getSender().getId(),
            dmMessage.getSentAt()
        );
    }

    // 콘텐츠 메시지 생성
    private static String getContentByType(String senderNickname, NotificationType type) {
        return switch (type) {
            case FRIEND_REQUESTED -> senderNickname + "님이 친구요청을 보냈습니다.";
            case FRIEND_ACCEPTED -> senderNickname + "님이 친구요청을 수락했습니다.";
            case FRIEND_REJECTED -> senderNickname + "님이 친구요청을 거절했습니다.";
            case WE_ARE_FRIEND_NOW -> senderNickname + "님과 친구가 되었습니다.";
            case CODE_REVIEW -> senderNickname + "님이 코드 리뷰를 추가했습니다.";
            case STUDY_APPLY -> senderNickname + "님이 스터디 참여를 신청했습니다.";
            case STUDY_APPROVED -> senderNickname + "님의 스터디 신청이 승인됐습니다! 🎉";
            case STUDY_REJECTED -> senderNickname + "님의 스터디 신청이 거절됐습니다.";
            default -> "딩동";
        };
    }
}
