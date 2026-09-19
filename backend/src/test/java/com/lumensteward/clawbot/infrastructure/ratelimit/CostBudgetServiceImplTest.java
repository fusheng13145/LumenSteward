package com.lumensteward.clawbot.infrastructure.ratelimit;

import com.lumensteward.clawbot.application.config.ConfigKeys;
import com.lumensteward.clawbot.application.config.DynamicConfigService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 成本预算（日 token 预算）服务单测（FR-20 ③）。
 *
 * <p>使用包级私有构造器注入固定时钟，隔离 Redis 计时逻辑。
 */
class CostBudgetServiceImplTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final ValueOperations<String, String> ops = mock(ValueOperations.class);
    private final DynamicConfigService config = mock(DynamicConfigService.class);
    private final Map<String, Long> counters = new HashMap<>();
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-19T00:00:00Z"), ZoneOffset.UTC);

    private CostBudgetServiceImpl build(long budget) {
        when(redis.opsForValue()).thenReturn(ops);
        when(config.getLong(eq(ConfigKeys.RATE_LIMIT_DAILY_TOKEN_BUDGET), eq(200_000L))).thenReturn(budget);
        when(ops.increment(anyString(), anyLong())).thenAnswer(inv ->
                counters.merge(inv.<String>getArgument(0), inv.<Long>getArgument(1), Long::sum));
        when(ops.get(anyString())).thenAnswer(inv -> {
            Long v = counters.get(inv.<String>getArgument(0));
            return v == null ? null : String.valueOf(v);
        });
        when(redis.expire(anyString(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(redis.hasKey(anyString())).thenReturn(false);
        return new CostBudgetServiceImpl(redis, config, clock);
    }

    @Test
    @DisplayName("零 token 调用不计数（不触发降级）")
    void shouldSkipZeroTokens() {
        CostBudgetServiceImpl svc = build(200_000);
        svc.recordLlmCall(0);
        verify(ops, never()).increment(anyString(), anyLong());
        assertThat(svc.isDegraded()).isFalse();
    }

    @Test
    @DisplayName("预算<=0 → 永不降级，百分比为 0")
    void shouldNeverDegradeWhenBudgetZero() {
        CostBudgetServiceImpl svc = build(0);
        svc.recordLlmCall(500_000);
        assertThat(svc.isDegraded()).isFalse();
        assertThat(svc.dailyUsagePercent()).isZero();
    }

    @Test
    @DisplayName("达 80% 触发 WARN（不降级），dailyUsagePercent 正确")
    void shouldWarnAtEightyPercent() {
        CostBudgetServiceImpl svc = build(200_000);
        svc.recordLlmCall(160_000);
        assertThat(svc.dailyUsagePercent()).isEqualTo(80);
        assertThat(svc.isDegraded()).isFalse();
    }

    @Test
    @DisplayName("达 100% 触发 DEGRADED：置 cost:degraded 且 isDegraded=true")
    void shouldDegradeAtHundredPercent() {
        CostBudgetServiceImpl svc = build(200_000);
        svc.recordLlmCall(160_000);
        svc.recordLlmCall(40_000);
        // 证明 DEGRADED 分支确实写入降级标志位
        verify(ops).set(eq("cost:degraded"), anyString(), anyLong(), isA(TimeUnit.class));
        // 模拟 Redis 已持久化降级标志位后查询
        when(redis.hasKey("cost:degraded")).thenReturn(true);
        assertThat(svc.isDegraded()).isTrue();
        assertThat(svc.dailyUsagePercent()).isEqualTo(100);
    }
}
