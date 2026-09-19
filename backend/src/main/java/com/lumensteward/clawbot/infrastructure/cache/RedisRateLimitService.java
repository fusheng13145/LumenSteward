package com.lumensteward.clawbot.infrastructure.cache;

import com.lumensteward.clawbot.application.config.ConfigKeys;
import com.lumensteward.clawbot.application.config.DynamicConfigService;
import com.lumensteward.clawbot.common.util.MaskUtils;
import com.lumensteward.clawbot.infrastructure.persistence.service.RateLimitLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Set;

/**
 * {@link RateLimitService} 的 Redis 实现（FR-20 / 8.3）。
 *
 * <p>维度：
 * <ul>
 *   <li>用户级令牌桶：{@code 20/分钟} + {@code 300/小时}（固定窗口近似，满足 AC①「1 分钟内 30 条第 21 条起限流」）；</li>
 *   <li>来源 IP 洪泛防护：{@code 300/分钟}（防伪造请求洪泛）；</li>
 * </ul>
 * 白名单（{@code rate_limit.whitelist}，逗号分隔 openid）豁免用户级限流（备选流 2a）。
 * Redis 不可用时 Fail-Open（BR-29）。超限事件落 {@code log_rate_limit}（FR-17 ④ 可检索）。
 */
@Service
public class RedisRateLimitService implements RateLimitService {

    private static final Logger log = LoggerFactory.getLogger(RedisRateLimitService.class);

    /** 用户维度前缀。 */
    public static final String USER_MINUTE_PREFIX = "rl:user:min:";
    /** 用户维度（小时）前缀。 */
    public static final String USER_HOUR_PREFIX = "rl:user:hour:";
    /** IP 维度前缀。 */
    public static final String IP_PREFIX = "rl:ip:";

    /** 单用户每分钟上限（SC-20）。 */
    public static final int USER_LIMIT_PER_MINUTE = 20;
    /** 单用户每小时上限（SC-20）。 */
    public static final int USER_LIMIT_PER_HOUR = 300;
    /** 单 IP 每分钟上限。 */
    public static final int IP_LIMIT_PER_MINUTE = 300;

    private static final Duration MINUTE_WINDOW = Duration.ofMinutes(1);
    private static final Duration HOUR_WINDOW = Duration.ofHours(1);

    private final StringRedisTemplate redisTemplate;
    private final DynamicConfigService dynamicConfig;
    private final RateLimitLogService rateLimitLogService;

    /**
     * 构造器注入（G-14）。
     *
     * @param redisTemplate       字符串 Redis 模板
     * @param dynamicConfig       动态配置（白名单，可为 null）
     * @param rateLimitLogService 限流事件日志（可为 null）
     */
    public RedisRateLimitService(StringRedisTemplate redisTemplate,
                                 DynamicConfigService dynamicConfig,
                                 RateLimitLogService rateLimitLogService) {
        this.redisTemplate = redisTemplate;
        this.dynamicConfig = dynamicConfig;
        this.rateLimitLogService = rateLimitLogService;
    }

    @Override
    public RateLimitDecision tryAcquire(String openid, String ip) {
        try {
            if (isWhitelisted(openid)) {
                return RateLimitDecision.ALLOWED;
            }
            if (openid != null && !openid.isBlank()) {
                if (!withinLimit(USER_MINUTE_PREFIX + openid, USER_LIMIT_PER_MINUTE, MINUTE_WINDOW)) {
                    return reject(RateLimitDecision.USER_FREQ, openid, ip);
                }
                if (!withinLimit(USER_HOUR_PREFIX + openid, USER_LIMIT_PER_HOUR, HOUR_WINDOW)) {
                    return reject(RateLimitDecision.USER_FREQ, openid, ip);
                }
            }
            if (ip != null && !ip.isBlank()
                    && !withinLimit(IP_PREFIX + ip, IP_LIMIT_PER_MINUTE, MINUTE_WINDOW)) {
                return reject(RateLimitDecision.IP_FREQ, openid, ip);
            }
            return RateLimitDecision.ALLOWED;
        } catch (RuntimeException e) {
            // Fail-Open：限流组件故障不得拒绝对话（BR-29）
            log.warn("Redis 限流不可用，Fail-Open: err={}", e.getMessage());
            return RateLimitDecision.ALLOWED;
        }
    }

    private boolean isWhitelisted(String openid) {
        if (openid == null || openid.isBlank() || dynamicConfig == null) {
            return false;
        }
        String raw = dynamicConfig.getString(ConfigKeys.RATE_LIMIT_WHITELIST, "");
        if (raw == null || raw.isBlank()) {
            return false;
        }
        Set<String> whitelist = Set.of(raw.split("\\s*,\\s*"));
        return whitelist.contains(openid);
    }

    private boolean withinLimit(String key, int limit, Duration window) {
        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redisTemplate.expire(key, window);
        }
        return count == null || count <= limit;
    }

    private RateLimitDecision reject(RateLimitDecision decision, String openid, String ip) {
        if (rateLimitLogService != null) {
            rateLimitLogService.record(MaskUtils.openid(openid), ip, decision.name(), LocalDateTime.now());
        }
        log.warn("触发限流 decision={} openid={}", decision, MaskUtils.openid(openid));
        return decision;
    }
}
