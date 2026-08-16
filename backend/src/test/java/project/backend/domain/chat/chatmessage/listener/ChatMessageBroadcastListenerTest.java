package project.backend.domain.chat.chatmessage.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import project.backend.domain.chat.chatmessage.dto.ChatMessageResponse;
import project.backend.domain.chat.chatmessage.dto.event.ChatMessageBroadcastEvent;
import project.backend.domain.chat.chatmessage.entity.ChatMessage;
import project.backend.domain.chat.chatmessage.entity.MessageType;
import project.backend.domain.chat.chatmessage.mapper.ChatMessageMapper;
import project.backend.domain.member.app.MemberService;
import project.backend.domain.member.app.ProfileImageCache;
import project.backend.domain.member.entity.Member;

@ExtendWith(MockitoExtension.class)
class ChatMessageBroadcastListenerTest {

    @Mock private SimpMessagingTemplate messagingTemplate;
    @Mock private ProfileImageCache profileImageCache;
    @Mock private MemberService memberService;
    @Spy private ChatMessageMapper messageMapper = new ChatMessageMapper();
    @InjectMocks private ChatMessageBroadcastListener listener;

    @Test
    @DisplayName("Redis 프로필 캐시가 비어 있으면 DB 프로필을 담아 채팅 메시지를 전송한다")
    void handleBroadcast_whenProfileCacheMiss_sendsDatabaseProfileImage() {
        ChatMessage message = ChatMessage.builder()
            .content("안녕하세요")
            .type(MessageType.TEXT)
            .createdAt(LocalDateTime.of(2026, 8, 16, 12, 0))
            .build();
        ChatMessageBroadcastEvent event = new ChatMessageBroadcastEvent(10L, 20L, "보낸이", message);
        Member sender = Member.builder().id(20L).profileImage("saved-profile.png").build();
        when(profileImageCache.getProfileImage(20L)).thenReturn(null);
        when(memberService.getMemberById(20L)).thenReturn(sender);

        listener.handleBroadcast(event);

        ArgumentCaptor<ChatMessageResponse> responseCaptor = ArgumentCaptor.forClass(
            ChatMessageResponse.class);
        verify(messagingTemplate).convertAndSend(org.mockito.ArgumentMatchers.eq("/topic/chat/10"),
            responseCaptor.capture());
        assertThat(responseCaptor.getValue().getProfileImageUrl()).isEqualTo("saved-profile.png");
        verify(profileImageCache).setProfileImage(20L, "saved-profile.png");
    }
}
