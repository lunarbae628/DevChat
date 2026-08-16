package project.backend.domain.member.app;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class ProfileImageCache {

    private final RedisTemplate<String, String> redisTemplate;

    private static final String MEMBER_PROFILE_KEY = "member:%d:profile";
    private static final long PROFILE_TTL_SEC = 60 * 60 * 24 * 7L; // 7일

    public void setProfileImage(Long memberId, String profileImg) {
        try {
            String key = String.format(MEMBER_PROFILE_KEY, memberId);
            redisTemplate.opsForValue().set(key, profileImg, PROFILE_TTL_SEC, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("Redis 장애 - 프로필 이미지 저장 실패 memberId={}", memberId);
        }
    }

    public String getProfileImage(Long memberId) {
        try {
            String key = String.format(MEMBER_PROFILE_KEY, memberId);
            return redisTemplate.opsForValue().get(key);
        } catch (Exception e) {
            log.warn("Redis 장애 - 프로필 이미지 조회 실패 memberId={}", memberId);
            return null;
        }
    }
}
