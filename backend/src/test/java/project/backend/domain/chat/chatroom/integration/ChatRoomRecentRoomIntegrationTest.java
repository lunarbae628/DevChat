package project.backend.domain.chat.chatroom.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import project.backend.domain.chat.chatroom.app.ChatRoomService;
import project.backend.domain.chat.chatroom.dao.ChatParticipantRepository;
import project.backend.domain.chat.chatroom.dao.ChatRoomAlarmRepository;
import project.backend.domain.chat.chatroom.dao.ChatRoomRepository;
import project.backend.domain.chat.chatroom.entity.ChatParticipant;
import project.backend.domain.chat.chatroom.entity.ChatRoom;
import project.backend.domain.chat.chatroom.entity.ChatRoomAlarm;
import project.backend.domain.member.dao.MemberRepository;
import project.backend.domain.member.entity.Member;
import project.backend.domain.member.entity.ProviderType;

@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class ChatRoomRecentRoomIntegrationTest {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>(DockerImageName.parse("mysql:8.4"))
        .withDatabaseName("testdb").withUsername("test").withPassword("test");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
        .withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired private ChatRoomService chatRoomService;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private MemberRepository memberRepository;
    @Autowired private ChatRoomRepository chatRoomRepository;
    @Autowired private ChatParticipantRepository chatParticipantRepository;
    @Autowired private ChatRoomAlarmRepository chatRoomAlarmRepository;

    @Test
    @DisplayName("채팅방 입장 뒤 최근 채팅방 ID가 다음 트랜잭션에서도 조회된다")
    void getEntryInfo_persistsRecentRoomId() {
        Long[] ids = transactionTemplate.execute(status -> {
            Member member = memberRepository.save(Member.builder()
                .username("recent-room-member")
                .nickname("최근방 사용자")
                .profileImage("default-profile.png")
                .provider(ProviderType.LOCAL)
                .build());
            ChatRoom room = chatRoomRepository.save(ChatRoom.builder()
                .name("최근 방")
                .inviteCode("RECENT-ROOM-CODE")
                .createdAt(LocalDateTime.of(2026, 8, 16, 12, 0))
                .build());
            chatParticipantRepository.save(ChatParticipant.builder()
                .participant(member)
                .chatRoom(room)
                .isOwner(true)
                .isActive(true)
                .joinAt(LocalDateTime.of(2026, 8, 16, 12, 0))
                .build());
            chatRoomAlarmRepository.save(new ChatRoomAlarm(member.getId(), room.getId()));
            return new Long[] {member.getId(), room.getId()};
        });

        chatRoomService.getEntryInfo("RECENT-ROOM-CODE", ids[0]);

        Long recentRoomId = transactionTemplate.execute(status -> memberRepository.findById(ids[0])
            .orElseThrow().getRecentRoomId());
        assertThat(recentRoomId).isEqualTo(ids[1]);
    }
}
