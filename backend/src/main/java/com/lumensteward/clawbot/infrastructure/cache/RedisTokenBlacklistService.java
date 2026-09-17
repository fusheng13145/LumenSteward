package com.lumensteward.clawbot.infrastructure.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * {@link TokenBlacklistService} 的 Redis 实现（8.3：{@code jwt:blacklist:{jti}}）。
 *
 * <p>Fail-Safe：Redis 不可用时保守视为「未在黑名单」会放行已登出 token，风险较高；但登出属低频，
 * 且鉴权主流程（签名/过期）仍在。此处记录 WARN 并返回 false，由安全团队后续以本地缓存兜底增强。
 */
@Service
public class RedisTokenBlacklistService implements TokenBlacklistService {

    private static final Logger log = LoggerFactory.getLogger(RedisTokenBlacklistService.class);

    /** key 前缀（8.3）。 */
    public static final String KEY_PREFIX = "jwt:blacklist:";

    private final StringRedisTemplate redisTemplate;

    /**
     * 构造器注入（G-14）。
     *
     * @param redisTemplate 字符串 Redis 模板
     */
    public RedisTokenBlacklistService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public void blacklist(String jti, long ttlSeconds) {
        if (jti == null || jti.isBlank()) {
            return;
        }
        long ttl = ttlSeconds <= 0 ? 3600L : ttlSeconds;
        try {
            redisTemplate.opsForValue().set(KEY_PREFIX + jti, "1", Duration.ofSeconds(ttl));
        } catch (RuntimeException e) {
            log.warn("Redis 写入黑名单失败: err={}", e.getMessage());
        }
    }

    @Override
    public boolean isBlacklisted(String jti) {
        if (jti == null || jti.isBlank()) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(KEY_PREFIX + jti));
        } catch (RuntimeException e) {
            log.warn("Redis 查询黑名单失败，按未命中处理: err={}", e.getMessage());
            return false;
        }
    }
}
