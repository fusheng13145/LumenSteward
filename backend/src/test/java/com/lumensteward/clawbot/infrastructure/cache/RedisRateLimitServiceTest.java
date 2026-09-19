package com.lumensteward.clawbot.infrastructure.cache;

import com.lumensteward.clawbot.application.config.ConfigKeys;
import com.lumensteward.clawbot.application.config.DynamicConfigService;
import com.lumensteward.clawbot.infrastructure.persistence.service.RateLimitLogService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 入站限流服务单测（FR-20 / BR-29）。
 */
class RedisRateLimitServiceTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final ValueOperations<String, String> ops = mock(ValueOperations.class);
    private final DynamicConfigService config = mock(DynamicConfigService.class);
    private final RateLimitLogService logService = mock(RateLimitLogService.class);
    private final Map<String, Long> counters = new HashMap<>();

    private RedisRateLimitService build() {
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.increment(anyString())).thenAnswer(inv ->
                counters.merge(inv.<String>getArgument(0), 1L, Long::sum));
        when(redis.expire(anyString(), any(Duration.class))).thenReturn(true);
        return new RedisRateLimitService(redis, config, logService);
    }

    @Test
    @DisplayName("用户令牌桶：20 次/分钟内全部放行，第 21 次触发 USER_FREQ 并落日志")
    void shouldAllowUpToLimitThenUserFreq() {
        RedisRateLimitService svc = build();
        for (int i = 0; i < 20; i++) {
            assertThat(svc.tryAcquire("openid1", "1.2.3.4")).isEqualTo(RateLimitDecision.ALLOWED);
        }
        assertThat(svc.tryAcquire("openid1", "1.2.3.4")).isEqualTo(RateLimitDecision.USER_FREQ);
        verify(logService).record(anyString(), eq("1.2.3.4"), eq("USER_FREQ"), any());
    }

    @Test
    @DisplayName("IP 洪泛防护：用户维度正常但 IP 超 300/分钟触发 IP_FREQ 并落日志")
    void shouldTriggerIpFreqWhenIpExceeds() {
        counters.put(RedisRateLimitService.IP_PREFIX + "9.9.9.9", 300L);
        RedisRateLimitService svc = build();
        assertThat(svc.tryAcquire("openidX", "9.9.9.9")).isEqualTo(RateLimitDecision.IP_FREQ);
        verify(logService).record(anyString(), eq("9.9.9.9"), eq("IP_FREQ"), any());
    }

    @Test
    @DisplayName("白名单 openid 豁免限流，不查询 Redis")
    void shouldBypassForWhitelisted() {
        when(config.getString(ConfigKeys.RATE_LIMIT_WHITELIST, "")).thenReturn("vip_user");
        RedisRateLimitService svc = build();
        assertThat(svc.tryAcquire("vip_user", "1.1.1.1")).isEqualTo(RateLimitDecision.ALLOWED);
        verify(ops, never()).increment(anyString());
    }

    @Test
    @DisplayName("Redis 不可用 → Fail-Open 放行（BR-29）")
    void shouldFailOpenOnRedisError() {
        when(redis.opsForValue()).thenThrow(new RuntimeException("redis down"));
        RedisRateLimitService svc = new RedisRateLimitService(redis, config, logService);
        assertThat(svc.tryAcquire("openid1", "1.2.3.4")).isEqualTo(RateLimitDecision.ALLOWED);
    }
}
