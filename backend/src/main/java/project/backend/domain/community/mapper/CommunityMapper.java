package project.backend.domain.community.mapper;

import project.backend.domain.chat.chatroom.dto.event.JoinChatRoomEvent;
import project.backend.domain.chat.chatmessage.entity.ChatMessage;
import project.backend.domain.chat.chatroom.entity.ChatRoom;
import project.backend.domain.community.dto.PostCreateRequest;
import project.backend.domain.community.dto.event.ApplicantResultEvent;
import project.backend.domain.community.dto.event.ApplyEvent;
import project.backend.domain.community.entity.Applicant;
import project.backend.domain.community.entity.Post;
import project.backend.domain.member.entity.Member;

import java.time.LocalDateTime;

public class CommunityMapper {

    public static Post toPost(PostCreateRequest request, Member author, ChatRoom chatRoom) {
        return Post.builder()
                .title(request.getTitle())
                .content(request.getContent())
                .maxCount(request.getMaxCount())
                .tag(request.getTag())
                .mode(request.getMode())
                .deadline(request.getDeadline())
                .techStacks(request.getTechStacks() != null
                        ? String.join(",", request.getTechStacks())
                        : null)
                .author(author)
                .chatRoom(chatRoom)
                .createdAt(LocalDateTime.now())
                .build();
    }

    public static ApplyEvent toApplyEvent(Post post, Member applicant) {
        return new ApplyEvent(
                post.getAuthor().getId(),
                post.getAuthor().getUsername(),
                applicant.getId(),
                applicant.getNickname(),
                post.getId(),
                post.getTitle()
        );
    }

    public static ApplicantResultEvent toApplicantResultEvent(Post post, Applicant applicant, boolean approved) {
        return new ApplicantResultEvent(
                applicant.getMember().getId(),
                applicant.getMember().getUsername(),
                post.getAuthor().getId(),
                post.getAuthor().getNickname(),
                post.getId(),
                post.getTitle(),
                approved
        );
    }

    public static JoinChatRoomEvent toJoinChatRoomEvent(Post post, Member member, ChatMessage savedMessage) {
        return new JoinChatRoomEvent(
                post.getChatRoom().getId(),
                member.getId(),
                member.getNickname(),
                savedMessage.getId(),
                savedMessage.getCreatedAt()
        );
    }
}
