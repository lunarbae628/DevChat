package project.backend.domain.chat.chatroom.app;

import java.util.*;
import java.util.stream.Collectors;

import jakarta.persistence.LockTimeoutException;
import jakarta.persistence.PessimisticLockException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.backend.domain.chat.chatmessage.app.ChatMessageService;
import project.backend.domain.chat.chatmessage.entity.ChatMessage;
import project.backend.domain.chat.chatroom.dao.ChatRoomRepository;
import project.backend.domain.chat.chatroom.dao.ChatRoomWithSequenceProjection;
import project.backend.domain.chat.chatroom.dto.*;
import project.backend.domain.chat.chatroom.dto.event.*;
import project.backend.domain.chat.chatroom.entity.*;
import project.backend.domain.chat.chatroom.mapper.ChatRoomMapper;
import project.backend.domain.chat.github.app.GitMessageService;
import project.backend.domain.community.dao.PostRepository;
import project.backend.domain.member.app.MemberService;
import project.backend.domain.member.entity.Member;
import project.backend.global.exception.errorcode.ChatRoomErrorCode;
import project.backend.global.exception.ex.ChatRoomException;
import project.backend.global.metric.TimeTrace;

@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class ChatRoomService {

    private final PostRepository postRepository;
    private final ChatRoomRepository chatRoomRepository;

    private final ChatRoomMapper chatRoomMapper;

    private final MemberService memberService;
    private final ChatMessageService chatMessageService;
    private final GitMessageService gitMessageService;
    private final ChatRoomSequenceService chatRoomSequenceService;
    private final ChatRoomAlarmService chatRoomAlarmService;
    private final ChatRoomReadService chatRoomReadService;
    private final ChatRoomParticipantService chatRoomParticipantService;

    private final ApplicationEventPublisher eventPublisher;

    @Value("${github.username}")
    private String githubUsername;

    @Transactional
    public ChatRoomSimpleResponse createChatRoom(ChatRoomRequest request, Long ownerId) {
        Member owner = memberService.getMemberById(ownerId);
        ChatRoom chatRoom = chatRoomMapper.toEntity(request);
        ChatParticipant chatParticipant = ChatParticipant.createOwner(owner, chatRoom);
        chatRoom.addParticipant(chatParticipant);
        ChatRoom savedRoom = chatRoomRepository.save(chatRoom);

        chatRoomAlarmService.createAlarm(ownerId, chatRoom.getId());

        if (!request.getRepositoryUrl().isBlank()) {
            gitMessageService.registerWebhook(request.getRepositoryUrl(),
                    savedRoom.getId(), owner.getId());
            joinGitHubBot(savedRoom);
        }

        return chatRoomMapper.toSimpleResponse(savedRoom, owner);
    }

    private void joinGitHubBot(ChatRoom room) {
        Member githubBot = memberService.getMemberByUsername(githubUsername);
        ChatParticipant gitParticipant = ChatParticipant.of(githubBot, room);
        room.addParticipant(gitParticipant);
    }

    @TimeTrace
    @Transactional
    public InviteJoinResponse joinChatRoom(String inviteCode, Long memberId) {
        log.info("timetrace 적용");
        try {
            ChatRoom room = getByInviteCodeWithLock(inviteCode);
            Member member = memberService.getMemberById(memberId);

            chatRoomParticipantService.handleParticipantJoin(room, member);

            chatRoomAlarmService.createAlarm(memberId, room.getId());
            chatRoomSequenceService.genMessageSeq(room.getId());

            ChatMessage savedMessage = chatMessageService.saveJoinEvent(room, member);

            eventPublisher.publishEvent(
                    new JoinChatRoomEvent(
                            room.getId(),
                            memberId,
                            member.getNickname(),
                            savedMessage.getId(),
                            savedMessage.getCreatedAt()
                    )
            );
            return ChatRoomMapper.toInviteJoinResponse(
                    room.getId(),
                    room.getInviteCode(),
                    room.getName()
            );
        } catch (PessimisticLockException | LockTimeoutException e) {
            throw new ChatRoomException(ChatRoomErrorCode.TRY_AGAIN);
        }
    }

    private ChatRoom getByInviteCodeWithLock(String inviteCode) {
        return chatRoomRepository.findByInviteCodeWithLock(inviteCode)
                .orElseThrow(() -> new ChatRoomException(ChatRoomErrorCode.CHATROOM_NOT_FOUND));
    }

    public String getRecentRoomInviteCode(Long memberId) {
        Long roomId = memberService.getMemberById(memberId).getRecentRoomId();
        if (roomId == null) {
            throw new ChatRoomException(ChatRoomErrorCode.CHATROOM_NOT_EXIST);
        }
        return getRoomById(roomId).getInviteCode();
    }

    public Page<MyChatRoomResponse> findAllRoomsByOwnerId(Long memberId, Pageable pageable) {
        return chatRoomRepository.findAllRoomsByOwnerId(memberId, pageable)
                .map(ChatRoomMapper::toProfileResponse);
    }

    public Page<RoomInfoResponse> findChatRoomsByMemberId(Long memberId, Pageable pageable) {
        return chatRoomRepository.findChatRoomsByParticipantId(memberId, pageable)
                .map(ChatRoomMapper::toListResponse);
    }

    @Transactional
    public void leaveChatRoom(Long roomId, Long memberId) {
        Member member = memberService.getMemberById(memberId);
        chatRoomParticipantService.leaveChatRoom(roomId, memberId, member.getNickname());
        updateRecentRoomAfterLeaving(memberId);
    }

    private void updateRecentRoomAfterLeaving(Long memberId) {
        Member member = memberService.getMemberById(memberId);
        chatRoomParticipantService.findTopRecentActiveRoom(memberId)
                .ifPresentOrElse(
                        p -> member.setRecentRoomId(p.getChatRoom().getId()),
                        () -> member.setRecentRoomId(null)
                );
    }

    public List<AllRoomsResponse> findAllRoomsByMemberId(Long memberId) {
        List<ChatRoomWithSequenceProjection> roomProjections =
                chatRoomRepository.findAllRoomsWithSequenceByParticipantId(memberId);

        List<Long> roomIds = roomProjections.stream()
                .map(ChatRoomWithSequenceProjection::getChatRoomId)
                .collect(Collectors.toList());

        Map<Long, Boolean> alarmEnabledMap = roomIds.isEmpty()
                ? Collections.emptyMap()
                : chatRoomAlarmService.findAlarmEnabledMap(memberId, roomIds);

        return chatRoomReadService.findAllRoomsWithUnread(roomProjections, alarmEnabledMap);
    }

    @Transactional
    public EntryRoomResponse getEntryInfo(String inviteCode, Long memberId) {
        ChatRoom room = getByInviteCode(inviteCode);

        ChatParticipant participant = chatRoomParticipantService
                .findActiveParticipant(room.getId(), memberId)
                .orElseThrow(() -> new ChatRoomException(ChatRoomErrorCode.NOT_PARTICIPANT));

        Long currentSequence = chatRoomReadService.getLatestSequence(room.getId());
        participant.updateLastReadSequence(currentSequence);

        memberService.getMemberById(memberId).setRecentRoomId(room.getId());

        Long ownerId = chatRoomParticipantService.findOwnerId(room.getId());
        boolean alarmEnable = chatRoomAlarmService.isAlarmEnabled(memberId, room.getId());

        return new EntryRoomResponse(room.getId(), room.getName(), ownerId, alarmEnable);
    }

    public RoomInfoResponse getRoomInfo(String inviteCode, Long memberId) {
        ChatRoom room = getByInviteCode(inviteCode);
        return ChatRoomMapper.toListResponse(room);
    }

    @Transactional
    public void deleteChatRoom(Long roomId, Long memberId) {
        ChatRoom room = getRoomById(roomId);

        ChatParticipant participant = chatRoomParticipantService
                .findActiveParticipant(roomId, memberId)
                .orElseThrow(() -> new ChatRoomException(ChatRoomErrorCode.NOT_PARTICIPANT));

        if (!participant.isOwner()) {
            throw new ChatRoomException(ChatRoomErrorCode.OWNER_PERMISSION_REQUIRED);
        }

        gitMessageService.deleteWebhook(room, memberId);
        eventPublisher.publishEvent(new DeleteChatRoomEvent(roomId, room.getName()));

        chatRoomParticipantService.deleteAllByRoomId(roomId);
        chatMessageService.deleteByRoomId(roomId);
        postRepository.deleteByChatRoom_Id(roomId);
        chatRoomRepository.delete(room);
    }

    @Transactional
    public boolean toggleAlarm(Long roomId, Long memberId) {
        return chatRoomAlarmService.toggleAlarm(roomId, memberId);
    }

    @Transactional
    public void updateLastReadSequence(Long roomId, Long memberId) {
        chatRoomReadService.updateLastReadSequence(roomId, memberId);
    }

    public ChatRoom getRoomById(Long roomId) {
        return chatRoomRepository.findById(roomId)
                .orElseThrow(() -> new ChatRoomException(ChatRoomErrorCode.CHATROOM_NOT_FOUND));
    }

    private ChatRoom getByInviteCode(String inviteCode) {
        return chatRoomRepository.findByInviteCode(inviteCode)
                .orElseThrow(() -> new ChatRoomException(ChatRoomErrorCode.CHATROOM_NOT_FOUND));
    }
}
