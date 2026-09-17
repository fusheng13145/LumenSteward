package com.lumensteward.clawbot.infrastructure.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * {@link RateLimitService} 的 Redis 实现（8.3：{@code rl:user:} / {@code rl:ip:}）。
 *
 * <p>固定窗口计数：按分钟窗口 {@code INCR} 并在首次设置 TTL。超限返回 false。
 * Redis 不可用时 Fail-Open（BR-29）。
 */
@Service
public class RedisRateLimitService implements RateLimitService {

    private static final Logger log = LoggerFactory.getLogger(RedisRateLimitService.class);

    /** 用户维度 key 前缀。 */
    public static final String USER_KEY_PREFIX = "rl:user:";
    /** IP 维度 key 前缀。 */
    public static final String IP_KEY_PREFIX = "rl:ip:";

    /** 单用户每分钟上限。 */
    private static final int USER_LIMIT_PER_MINUTE = 60;
    /** 单 IP 每分钟上限。 */
    private static final int IP_LIMIT_PER_MINUTE = 300;
    /** 窗口长度。 */
    private static final Duration WINDOW = Duration.ofMinutes(1);

    private final StringRedisTemplate redisTemplate;

    /**
     * 构造器注入（G-14）。
     *
     * @param redisTemplate 字符串 Redis 模板
     */
    public RedisRateLimitService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public boolean tryAcquire(String openid, String ip) {
        try {
            if (openid != null && !openid.isBlank()
                    && !incrementWithinLimit(USER_KEY_PREFIX + openid, USER_LIMIT_PER_MINUTE)) {
                return false;
            }
            if (ip != null && !ip.isBlank()
                    && !incrementWithinLimit(IP_KEY_PREFIX + ip, IP_LIMIT_PER_MINUTE)) {
                return false;
            }
            return true;
        } catch (RuntimeException e) {
            log.warn("Redis 限流不可用，Fail-Open: err={}", e.getMessage());
            return true;
        }
    }

    private boolean incrementWithinLimit(String key, int limit) {
        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redisTemplate.expire(key, WINDOW);
        }
        return count == null || count <= limit;
    }
}
