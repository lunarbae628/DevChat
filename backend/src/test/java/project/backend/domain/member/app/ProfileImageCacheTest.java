package project.backend.domain.member.app;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class ProfileImageCacheTest {

    @Test
    @DisplayName("Redis 저장 실패는 프로필 캐시 갱신 호출자에게 전파되지 않는다")
    @SuppressWarnings("unchecked")
    void setProfileImage_whenRedisWriteFails_doesNotPropagate() {
        RedisTemplate<String, String> redisTemplate = org.mockito.Mockito.mock(RedisTemplate.class);
        ValueOperations<String, String> valueOperations = org.mockito.Mockito.mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        doThrow(new RedisConnectionFailureException("redis unavailable"))
            .when(valueOperations).set(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any());
        ProfileImageCache cache = new ProfileImageCache(redisTemplate);

        assertThatCode(() -> cache.setProfileImage(20L, "saved-profile.png"))
            .doesNotThrowAnyException();
    }
}
