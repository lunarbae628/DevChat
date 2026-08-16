package project.backend.global.config.async;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Slf4j
@Configuration
@EnableAsync
@RequiredArgsConstructor
@EnableConfigurationProperties(NotificationDeliveryProperties.class)
public class AsyncConfig {

    private final CustomRejectedExecutionHandler customRejectedExecutionHandler;
    private final MeterRegistry meterRegistry;

    @Bean(name = "chatBroadcastExecutor")
    public Executor getChatBroadcastExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(10);
        executor.setMaxPoolSize(50);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("ChatBroadcast-");
        executor.setRejectedExecutionHandler(customRejectedExecutionHandler);
        executor.initialize();
        return executor;
    }

    @Bean(name = "chatRoomEventExecutor")
    public Executor getChatRoomEventExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(5); // 최소 스레드 수
        executor.setMaxPoolSize(20); // 최대 스레드 수
        executor.setQueueCapacity(100); // 작업 대기열
        executor.setThreadNamePrefix("ChatRoomEvent-");
        executor.setRejectedExecutionHandler(customRejectedExecutionHandler);
        executor.initialize();
        return executor;
    }

    @Bean(name = "chatSeqExecutor")
    public Executor getChatSeqExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(5);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("ChatSeq-");
        executor.setRejectedExecutionHandler((r, e) -> {
                meterRegistry.counter("chat.seq.executor.rejected").increment();
                log.warn("seq 채번 큐 포화 - 드랍 처리 (풀 크기 조정 필요)");
        });
        executor.initialize();
        return executor;
    }

    @Bean(name = "notificationDeliveryExecutor")
    public Executor getNotificationDeliveryExecutor(NotificationDeliveryProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.corePoolSize());
        executor.setMaxPoolSize(properties.maxPoolSize());
        executor.setQueueCapacity(properties.queueCapacity());
        executor.setThreadNamePrefix("NotificationDelivery-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }

    @Bean(name = "notificationAlertExecutor")
    public Executor getNotificationAlertExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("NotificationAlert-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }
}
