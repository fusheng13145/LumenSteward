package com.lumensteward.clawbot.infrastructure.ratelimit;

import com.lumensteward.clawbot.application.config.ConfigKeys;
import com.lumensteward.clawbot.application.config.DynamicConfigService;
import com.lumensteward.clawbot.application.ratelimit.CostBudgetService.LlmCallUsage;
import com.lumensteward.clawbot.common.enums.LlmCallPurpose;
import com.lumensteward.clawbot.infrastructure.persistence.service.LlmCallLogService;
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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 成本预算（日 token 预算）服务单测（FR-20 ③ / B-4）。
 *
 * <p>使用包级私有构造器注入固定时钟，隔离 Redis 计时逻辑；明细写入以 mock 替身断言
 * 「同一次调用两条口径同时前进」（W5 的看板数值必须能与 SQL 对得上）。
 */
class CostBudgetServiceImplTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final ValueOperations<String, String> ops = mock(ValueOperations.class);
    private final DynamicConfigService config = mock(DynamicConfigService.class);
    private final LlmCallLogService detail = mock(LlmCallLogService.class);
    private final Map<String, Long> counters = new HashMap<>();
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-19T00:00:00Z"), ZoneOffset.UTC);

    /** 构造一次调用计量：prompt=tokens、completion=0，便于断言合计。 */
    private static LlmCallUsage usage(int tokens) {
        return new LlmCallUsage(LlmCallPurpose.CHAT, "mock", "test-model", "openid-1",
                42L, "trace-1", tokens, 0);
    }

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
        return new CostBudgetServiceImpl(redis, config, detail, clock);
    }

    @Test
    @DisplayName("零 token 调用不计数（不触发降级）")
    void shouldSkipZeroTokens() {
        CostBudgetServiceImpl svc = build(200_000);
        svc.recordLlmCall(usage(0));
        verify(ops, never()).increment(anyString(), anyLong());
        assertThat(svc.isDegraded()).isFalse();
    }

    @Test
    @DisplayName("预算<=0 → 永不降级，百分比为 0")
    void shouldNeverDegradeWhenBudgetZero() {
        CostBudgetServiceImpl svc = build(0);
        svc.recordLlmCall(usage(500_000));
        assertThat(svc.isDegraded()).isFalse();
        assertThat(svc.dailyUsagePercent()).isZero();
    }

    @Test
    @DisplayName("达 80% 触发 WARN（不降级），dailyUsagePercent 正确")
    void shouldWarnAtEightyPercent() {
        CostBudgetServiceImpl svc = build(200_000);
        svc.recordLlmCall(usage(160_000));
        assertThat(svc.dailyUsagePercent()).isEqualTo(80);
        assertThat(svc.isDegraded()).isFalse();
    }

    @Test
    @DisplayName("达 100% 触发 DEGRADED：isDegraded 实时为真，cost:degraded 仅作日志去重标记")
    void shouldDegradeAtHundredPercent() {
        CostBudgetServiceImpl svc = build(200_000);
        svc.recordLlmCall(usage(160_000));
        svc.recordLlmCall(usage(40_000));
        // 降级只告警一次依赖该标志位，但它不再是 isDegraded 的依据
        verify(ops).set(eq("cost:degraded"), anyString(), anyLong(), isA(TimeUnit.class));
        assertThat(svc.isDegraded()).isTrue();
        assertThat(svc.dailyUsagePercent()).isEqualTo(100);
    }

    @Test
    @DisplayName("W5 实测缺陷修复：isDegraded 实时跟随预算，抬高即退出降级、调低即进入")
    void degradeFollowsBudgetLive() {
        CostBudgetServiceImpl svc = build(200_000);
        svc.recordLlmCall(usage(200_000));
        // 粘滞标志位仍"在"（TTL 到午夜）：旧实现据此把「额度已用完」一路说到第二天
        when(redis.hasKey("cost:degraded")).thenReturn(true);
        assertThat(svc.isDegraded()).isTrue();

        when(config.getLong(eq(ConfigKeys.RATE_LIMIT_DAILY_TOKEN_BUDGET), eq(200_000L)))
                .thenReturn(1_000_000L);
        assertThat(svc.isDegraded()).isFalse();
        assertThat(svc.dailyUsagePercent()).isEqualTo(20);

        when(config.getLong(eq(ConfigKeys.RATE_LIMIT_DAILY_TOKEN_BUDGET), eq(200_000L)))
                .thenReturn(100_000L);
        assertThat(svc.isDegraded()).isTrue();
    }

    @Test
    @DisplayName("B-4：明细与计数同源——同一次调用既写 log_llm_call 也累加日桶")
    void writesDetailAndCounterTogether() {
        CostBudgetServiceImpl svc = build(200_000);
        LlmCallUsage usage = usage(1_200);

        svc.recordLlmCall(usage);

        verify(detail).record(usage);
        assertThat(counters.get("cost:llm:daily:2026-09-19")).isEqualTo(1_200L);
    }

    @Test
    @DisplayName("B-4：0 token 调用仍落明细（Mock/缺 usage 时次数可见），但不进预算桶")
    void writesDetailForZeroTokens() {
        CostBudgetServiceImpl svc = build(200_000);

        svc.recordLlmCall(usage(0));

        verify(detail).record(any());
        verify(ops, never()).increment(anyString(), anyLong());
    }

    @Test
    @DisplayName("B-4：明细写入抛异常不吞掉预算计数（两条口径各自兜底）")
    void detailFailureDoesNotSkipCounter() {
        CostBudgetServiceImpl svc = build(200_000);
        doThrow(new IllegalStateException("db down")).when(detail).record(any());

        svc.recordLlmCall(usage(900));

        assertThat(counters.get("cost:llm:daily:2026-09-19")).isEqualTo(900L);
    }

    @Test
    @DisplayName("usage 为 null 或缺用途：整体忽略，不写库不计数")
    void ignoresIncompleteUsage() {
        CostBudgetServiceImpl svc = build(200_000);

        svc.recordLlmCall(null);
        svc.recordLlmCall(new LlmCallUsage(null, "mock", "m", null, null, null, 100, 0));

        verify(detail, never()).record(any());
        verify(ops, never()).increment(anyString(), anyLong());
    }
}
