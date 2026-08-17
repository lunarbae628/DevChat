package project.backend.domain.chat.chatroom.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.*;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import project.backend.domain.chat.chatmessage.app.ChatMessageService;
import project.backend.domain.chat.chatmessage.entity.ChatMessage;
import project.backend.domain.chat.chatroom.dao.ChatRoomRepository;
import project.backend.domain.chat.chatroom.dao.ChatRoomWithSequenceProjection;
import project.backend.domain.chat.chatroom.dto.AllRoomsResponse;
import project.backend.domain.chat.chatroom.dto.ChatRoomRequest;
import project.backend.domain.chat.chatroom.dto.ChatRoomSimpleResponse;
import project.backend.domain.chat.chatroom.dto.InviteJoinResponse;
import project.backend.domain.chat.chatroom.dto.event.DeleteChatRoomEvent;
import project.backend.domain.chat.chatroom.dto.event.JoinChatRoomEvent;
import project.backend.domain.chat.chatroom.entity.ChatParticipant;
import project.backend.domain.chat.chatroom.entity.ChatRoom;
import project.backend.domain.chat.chatroom.mapper.ChatRoomMapper;
import project.backend.domain.chat.github.app.GitMessageService;
import project.backend.domain.community.dao.PostRepository;
import project.backend.domain.member.app.MemberService;
import project.backend.domain.member.entity.Member;
import project.backend.global.exception.ex.ChatRoomException;

@ExtendWith(MockitoExtension.class)
class ChatRoomServiceTest {

    @InjectMocks
    private ChatRoomService chatRoomService;

    @Mock private PostRepository postRepository;
    @Mock private ChatRoomRepository chatRoomRepository;
    @Mock private ChatRoomMapper chatRoomMapper;
    @Mock private MemberService memberService;
    @Mock private ChatMessageService chatMessageService;
    @Mock private GitMessageService gitMessageService;
    @Mock private ChatRoomSequenceService chatRoomSequenceService;
    @Mock private ChatRoomAlarmService chatRoomAlarmService;
    @Mock private ChatRoomReadService chatRoomReadService;
    @Mock private ChatRoomParticipantService chatRoomParticipantService;
    @Mock private ApplicationEventPublisher eventPublisher;

    private Member owner;
    private ChatRoom chatRoom;
    private ChatParticipant ownerParticipant;
    private ChatParticipant memberParticipant;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(chatRoomService, "githubUsername", "github-bot");
        owner = mock(Member.class);
        chatRoom = mock(ChatRoom.class);
        ownerParticipant = mock(ChatParticipant.class);
        memberParticipant = mock(ChatParticipant.class);
    }

    @Nested
    @DisplayName("createChatRoom() - 채팅방 생성")
    class CreateChatRoom {

        @Test
        @DisplayName("레포지토리 URL 없이 채팅방을 생성하면 웹훅 등록 없이 방이 생성된다")
        void createChatRoom_noRepository_success() {
            ChatRoomRequest request = mock(ChatRoomRequest.class);
            given(request.getRepositoryUrl()).willReturn("");
            given(memberService.getMemberById(1L)).willReturn(owner);
            given(chatRoomMapper.toEntity(request)).willReturn(chatRoom);
            given(chatRoomRepository.save(chatRoom)).willReturn(chatRoom);
            ChatRoomSimpleResponse expected = mock(ChatRoomSimpleResponse.class);
            given(chatRoomMapper.toSimpleResponse(chatRoom, owner)).willReturn(expected);

            ChatRoomSimpleResponse result = chatRoomService.createChatRoom(request, 1L);

            assertThat(result).isEqualTo(expected);
            then(gitMessageService).should(never()).registerWebhook(anyString(), anyLong(), anyLong());
        }

        @Test
        @DisplayName("레포지토리 URL이 없으면 null이어도 웹훅 등록 없이 방이 생성된다")
        void createChatRoom_nullRepository_success() {
            ChatRoomRequest request = mock(ChatRoomRequest.class);
            given(request.getRepositoryUrl()).willReturn(null);
            given(memberService.getMemberById(1L)).willReturn(owner);
            given(chatRoomMapper.toEntity(request)).willReturn(chatRoom);
            given(chatRoomRepository.save(chatRoom)).willReturn(chatRoom);
            ChatRoomSimpleResponse expected = mock(ChatRoomSimpleResponse.class);
            given(chatRoomMapper.toSimpleResponse(chatRoom, owner)).willReturn(expected);

            ChatRoomSimpleResponse result = chatRoomService.createChatRoom(request, 1L);

            assertThat(result).isEqualTo(expected);
            then(gitMessageService).should(never()).registerWebhook(anyString(), anyLong(), anyLong());
            then(memberService).should(never()).getMemberByUsername("github-bot");
        }

        @Test
        @DisplayName("레포지토리 URL이 있으면 웹훅 등록과 깃허브봇 참가가 실행된다")
        void createChatRoom_withRepository_registersWebhookAndBot() {
            given(chatRoom.getId()).willReturn(10L);
            given(owner.getId()).willReturn(1L);

            ChatRoomRequest request = mock(ChatRoomRequest.class);
            given(request.getRepositoryUrl()).willReturn("https://github.com/test/repo");
            Member githubBot = mock(Member.class);
            given(memberService.getMemberById(1L)).willReturn(owner);
            given(memberService.getMemberByUsername("github-bot")).willReturn(githubBot);
            given(chatRoomMapper.toEntity(request)).willReturn(chatRoom);
            given(chatRoomRepository.save(chatRoom)).willReturn(chatRoom);
            given(chatRoomMapper.toSimpleResponse(chatRoom, owner)).willReturn(mock(ChatRoomSimpleResponse.class));

            chatRoomService.createChatRoom(request, 1L);

            then(gitMessageService).should().registerWebhook("https://github.com/test/repo", 10L, 1L);
            then(chatRoom).should(times(2)).addParticipant(any(ChatParticipant.class));
        }
    }

    @Nested
    @DisplayName("joinChatRoom() - 초대 코드로 채팅방 참가")
    class JoinChatRoom {

        @Test
        @DisplayName("새 멤버가 정상적으로 채팅방에 참가한다")
        void joinChatRoom_newMember_success() {
            given(chatRoom.getId()).willReturn(10L);
            given(chatRoom.getInviteCode()).willReturn("INVITE-CODE");
            given(chatRoom.getName()).willReturn("테스트 방");

            Member joiner = mock(Member.class);
            given(joiner.getNickname()).willReturn("joinerNick");

            given(chatRoomRepository.findByInviteCodeWithLock("INVITE-CODE")).willReturn(Optional.of(chatRoom));
            given(memberService.getMemberById(2L)).willReturn(joiner);
            given(chatRoomSequenceService.genMessageSeq(10L)).willReturn(1L); // 수정: memberId 추가

            ChatMessage joinMessage = mock(ChatMessage.class);
            given(joinMessage.getId()).willReturn(500L);
            given(joinMessage.getCreatedAt()).willReturn(java.time.LocalDateTime.now());
            given(chatMessageService.saveJoinEvent(chatRoom, joiner)).willReturn(joinMessage);

            InviteJoinResponse result = chatRoomService.joinChatRoom("INVITE-CODE", 2L);

            assertThat(result.getId()).isEqualTo(10L);
            assertThat(result.getInviteCode()).isEqualTo("INVITE-CODE");
            then(eventPublisher).should().publishEvent(any(JoinChatRoomEvent.class));
        }

        @Test
        @DisplayName("존재하지 않는 초대 코드로 참가하면 예외를 던진다")
        void joinChatRoom_invalidCode_throwsException() {
            given(chatRoomRepository.findByInviteCodeWithLock("WRONG-CODE")).willReturn(Optional.empty());

            assertThatThrownBy(() -> chatRoomService.joinChatRoom("WRONG-CODE", 2L))
                    .isInstanceOf(ChatRoomException.class);
        }
    }

    @Nested
    @DisplayName("leaveChatRoom() - 채팅방 나가기")
    class LeaveChatRoom {

        @Test
        @DisplayName("일반 참가자는 채팅방을 정상적으로 나간다")
        void leaveChatRoom_normalMember_success() {
            Member leavingMember = mock(Member.class);
            given(leavingMember.getNickname()).willReturn("leaverNick");
            given(memberService.getMemberById(2L)).willReturn(leavingMember); // 2번 호출되므로 한 번 설정으로 커버
            given(chatRoomParticipantService.findTopRecentActiveRoom(2L)).willReturn(Optional.empty());

            chatRoomService.leaveChatRoom(10L, 2L);

            then(chatRoomParticipantService).should().leaveChatRoom(10L, 2L, "leaverNick");
            then(leavingMember).should().setRecentRoomId(null);
        }

        @Test
        @DisplayName("방 나가기 후 남은 활성 방이 있으면 recentRoomId를 업데이트한다")
        void leaveChatRoom_hasOtherRoom_updatesRecentRoom() {
            Member leavingMember = mock(Member.class);
            given(leavingMember.getNickname()).willReturn("leaverNick");
            given(memberService.getMemberById(2L)).willReturn(leavingMember);

            ChatParticipant otherRoomParticipant = mock(ChatParticipant.class);
            ChatRoom otherRoom = mock(ChatRoom.class);
            given(otherRoom.getId()).willReturn(20L);
            given(otherRoomParticipant.getChatRoom()).willReturn(otherRoom);
            given(chatRoomParticipantService.findTopRecentActiveRoom(2L))
                    .willReturn(Optional.of(otherRoomParticipant));

            chatRoomService.leaveChatRoom(10L, 2L);

            then(leavingMember).should().setRecentRoomId(20L);
        }
    }

    @Nested
    @DisplayName("deleteChatRoom() - 채팅방 삭제")
    class DeleteChatRoom {

        @Test
        @DisplayName("방장이 채팅방을 삭제하면 관련 데이터가 모두 삭제된다")
        void deleteChatRoom_owner_success() {
            given(ownerParticipant.isOwner()).willReturn(true);

            given(chatRoomRepository.findById(10L)).willReturn(Optional.of(chatRoom));
            given(chatRoomParticipantService.findActiveParticipant(10L, 1L))
                    .willReturn(Optional.of(ownerParticipant));

            chatRoomService.deleteChatRoom(10L, 1L);

            then(gitMessageService).should().deleteWebhook(chatRoom, 1L);
            then(eventPublisher).should().publishEvent(any(DeleteChatRoomEvent.class));
            then(chatRoomParticipantService).should().deleteAllByRoomId(10L);
            then(chatMessageService).should().deleteByRoomId(10L);
            then(postRepository).should().deleteByChatRoom_Id(10L);
            then(chatRoomRepository).should().delete(chatRoom);
        }

        @Test
        @DisplayName("일반 멤버가 삭제를 시도하면 예외를 던진다")
        void deleteChatRoom_notOwner_throwsException() {
            given(memberParticipant.isOwner()).willReturn(false);

            given(chatRoomRepository.findById(10L)).willReturn(Optional.of(chatRoom));
            given(chatRoomParticipantService.findActiveParticipant(10L, 2L))
                    .willReturn(Optional.of(memberParticipant));

            assertThatThrownBy(() -> chatRoomService.deleteChatRoom(10L, 2L))
                    .isInstanceOf(ChatRoomException.class);
            then(chatRoomRepository).should(never()).delete(any());
        }

        @Test
        @DisplayName("존재하지 않는 채팅방을 삭제하면 예외를 던진다")
        void deleteChatRoom_roomNotFound_throwsException() {
            given(chatRoomRepository.findById(999L)).willReturn(Optional.empty());

            assertThatThrownBy(() -> chatRoomService.deleteChatRoom(999L, 1L))
                    .isInstanceOf(ChatRoomException.class);
        }
    }

    @Nested
    @DisplayName("findAllRoomsByMemberId() - 내 채팅방 목록 조회")
    class FindAllRoomsByMemberId {

        @Test
        @DisplayName("정상적으로 채팅방 목록과 안읽음 수를 반환한다")
        void findAllRooms_success() {
            ChatRoomWithSequenceProjection projection = mock(ChatRoomWithSequenceProjection.class);
            given(projection.getChatRoomId()).willReturn(10L);
            given(chatRoomRepository.findAllRoomsWithSequenceByParticipantId(1L))
                    .willReturn(List.of(projection));
            given(chatRoomAlarmService.findAlarmEnabledMap(1L, List.of(10L)))
                    .willReturn(Map.of(10L, true));

            AllRoomsResponse expected = mock(AllRoomsResponse.class);
            given(chatRoomReadService.findAllRoomsWithUnread(any(), any()))
                    .willReturn(List.of(expected));

            List<AllRoomsResponse> result = chatRoomService.findAllRoomsByMemberId(1L);

            assertThat(result).hasSize(1);
            assertThat(result.get(0)).isEqualTo(expected);
        }

        @Test
        @DisplayName("참가한 채팅방이 없으면 빈 리스트를 반환한다")
        void findAllRooms_empty_returnsEmptyList() {
            given(chatRoomRepository.findAllRoomsWithSequenceByParticipantId(1L))
                    .willReturn(Collections.emptyList());
            given(chatRoomReadService.findAllRoomsWithUnread(any(), any()))
                    .willReturn(Collections.emptyList());

            List<AllRoomsResponse> result = chatRoomService.findAllRoomsByMemberId(1L);

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("getRecentRoomInviteCode() - 최근 채팅방 초대 코드 조회")
    class GetRecentRoomInviteCode {

        @Test
        @DisplayName("최근 방이 있으면 초대 코드를 반환한다")
        void getRecentRoomInviteCode_exists_returnsCode() {
            given(chatRoom.getInviteCode()).willReturn("INVITE-CODE");
            given(owner.getRecentRoomId()).willReturn(10L);
            given(memberService.getMemberById(1L)).willReturn(owner);
            given(chatRoomRepository.findById(10L)).willReturn(Optional.of(chatRoom));

            String result = chatRoomService.getRecentRoomInviteCode(1L);

            assertThat(result).isEqualTo("INVITE-CODE");
        }

        @Test
        @DisplayName("최근 방이 null이면 예외를 던진다")
        void getRecentRoomInviteCode_null_throwsException() {
            given(owner.getRecentRoomId()).willReturn(null);
            given(memberService.getMemberById(1L)).willReturn(owner);

            assertThatThrownBy(() -> chatRoomService.getRecentRoomInviteCode(1L))
                    .isInstanceOf(ChatRoomException.class);
        }
    }
}
