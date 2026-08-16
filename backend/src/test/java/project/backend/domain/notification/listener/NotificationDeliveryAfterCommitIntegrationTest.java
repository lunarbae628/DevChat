package project.backend.domain.notification.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import project.backend.domain.notification.dto.NotificationDto;
import project.backend.domain.notification.entity.NotificationType;

@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class NotificationDeliveryAfterCommitIntegrationTest {

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

    @Autowired ApplicationEventPublisher eventPublisher;
    @Autowired TransactionTemplate transactionTemplate;
    @MockitoBean SimpMessagingTemplate messagingTemplate;

    @Test
    @DisplayName("알림 이벤트는 트랜잭션 커밋 뒤 전용 executor에서 WebSocket으로 전송한다")
    void notificationEvent_isDeliveredAfterCommitOnNotificationExecutor() throws InterruptedException {
        NotificationDto event = new NotificationDto(1L, false, NotificationType.STUDY_APPLY,
            "receiver", "sender", "보낸이", "profile.png", "스터디 신청", 2L,
            LocalDateTime.of(2026, 8, 14, 12, 0));
        CountDownLatch delivered = new CountDownLatch(1);
        AtomicReference<String> threadName = new AtomicReference<>();
        doAnswer(invocation -> {
            threadName.set(Thread.currentThread().getName());
            delivered.countDown();
            return null;
        }).when(messagingTemplate).convertAndSend(eq("/topic/notifications/receiver"), eq(event));

        transactionTemplate.executeWithoutResult(status -> {
            eventPublisher.publishEvent(event);
            verifyNoInteractions(messagingTemplate);
        });

        assertThat(delivered.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(threadName.get()).startsWith("NotificationDelivery-");
    }
}
