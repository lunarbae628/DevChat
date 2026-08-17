package project.backend.domain.chat.github.app;

import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.backend.auth.app.AuthTokenService;
import project.backend.domain.chat.chatmessage.dao.ChatMessageRepository;
import project.backend.domain.chat.chatmessage.dto.ChatMessageResponse;
import project.backend.domain.chat.chatmessage.entity.ChatMessage;
import project.backend.domain.chat.chatmessage.mapper.ChatMessageMapper;
import project.backend.domain.chat.chatroom.dao.ChatRoomRedisRepository;
import project.backend.domain.chat.chatroom.dao.ChatRoomRepository;
import project.backend.domain.chat.chatroom.entity.ChatRoom;
import project.backend.domain.chat.github.GitHubClient;
import project.backend.domain.chat.github.GitRepoUrlUtils;
import project.backend.domain.chat.github.dto.GitMessageDto;
import project.backend.domain.chat.github.dto.GitRepoDto;
import project.backend.domain.member.app.MemberService;
import project.backend.domain.member.entity.Member;
import project.backend.global.exception.errorcode.ChatRoomErrorCode;
import project.backend.global.exception.ex.ChatRoomException;

@Service
@Slf4j
@RequiredArgsConstructor
public class GitMessageService {

	private final ChatRoomRepository chatRoomRepository;
	private final ChatMessageMapper chatMessageMapper;
	private final ChatMessageRepository chatMessageRepository;
	private final SimpMessagingTemplate messagingTemplate;
	private final MemberService memberService;
	private final GitHubClient gitHubClient;
	private final AuthTokenService authTokenService;
	private final ChatRoomRedisRepository chatRoomRedisRepository;

	@Value("${url.webhook-url}")
	private String webhookUrl;

	@Value("${github.username}")
	private String githubUsername;

	@Transactional
	public void handleEvent(Long roomId, String eventType, Map<String, Object> payload) {
		GitMessageDto gitMessage = switch (eventType) {
			case "issues" -> GitMessageDto.fromIssue(payload);
			case "pull_request" -> GitMessageDto.fromPullRequest(payload);
			case "pull_request_review" -> GitMessageDto.fromPullRequestReview(payload);
			case "workflow_run" -> GitMessageDto.fromWorkflowRun(payload);
			default -> null;
		};

		if (gitMessage == null) return;

		ChatRoom room = chatRoomRepository.findById(roomId)
				.orElseThrow(() -> new ChatRoomException(ChatRoomErrorCode.CHATROOM_NOT_FOUND));

		sendGitMessage(room, gitMessage.attachRoom(gitMessage, room));
	}

	private void sendGitMessage(ChatRoom room, GitMessageDto gitMessage) {
		Member githubBot = memberService.getMemberByUsername(githubUsername);
		chatRoomRedisRepository.genMessageSeq(room.getId());
		ChatMessage message = chatMessageMapper.toEntityWithGit(gitMessage, githubBot);
		chatMessageRepository.save(message);
		ChatMessageResponse response = chatMessageMapper.toGitResponse(message);
		messagingTemplate.convertAndSend("/topic/chat/" + room.getId(), response);
	}

	public void registerWebhook(String repoUrl, Long roomId, Long memberId) {
		GitRepoDto gitRepoDto = GitRepoUrlUtils.validateAndParseUrl(repoUrl);

		log.info("owner = {}", gitRepoDto.ownerName());
		log.info("repo = {}", gitRepoDto.repoName());

		String githubAccessToken = authTokenService.getGithubAccessToken(memberId);

		String webhookUrl = makeWebhookUrl(roomId);

		Long webhookId = gitHubClient.registerWebhook(githubAccessToken,
				gitRepoDto.ownerName(), gitRepoDto.repoName(), webhookUrl);

		ChatRoom room = chatRoomRepository.findById(roomId)
				.orElseThrow(() -> new ChatRoomException(ChatRoomErrorCode.CHATROOM_NOT_FOUND));

		room.updateWebhookId(webhookId);
	}

	private String makeWebhookUrl(Long roomId) {
		return webhookUrl + "/github/" + roomId;
	}

	public void deleteWebhook(ChatRoom room, Long ownerId) {
		if (room.getRepositoryUrl() == null || room.getWebhookId() == null) {
			log.info("GitHub 연동이 없는 채팅방입니다. 웹훅 삭제 스킵: roomId={}", room.getId());
			return;
		}

		GitRepoDto gitRepoDto = GitRepoUrlUtils.validateAndParseUrl(room.getRepositoryUrl());

		String githubAccessToken = authTokenService.getGithubAccessToken(ownerId);

		gitHubClient.deleteWebhook(githubAccessToken,
				gitRepoDto.ownerName(), gitRepoDto.repoName(), room.getWebhookId());
	}
}