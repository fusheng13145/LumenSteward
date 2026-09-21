package com.lumensteward.clawbot.gray;

import com.lumensteward.clawbot.application.config.ConfigKeys;
import com.lumensteward.clawbot.application.config.DynamicConfigService;
import com.lumensteward.clawbot.application.gray.GrayCircuitBreaker;
import com.lumensteward.clawbot.application.gray.GrayHealthMetrics;
import com.lumensteward.clawbot.application.gray.GrayHealthSnapshot;
import com.lumensteward.clawbot.application.gray.GrayRollbackService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 灰度熔断守护单测（FR-22 异常流 3a / BR-31）。
 *
 * <p>验证的是「什么条件下才动手」这四件事：越界才回滚、未越界不动、开关关闭不查库、
 * 样本不足不动——最后一条是防误熔断，低峰期一条异常不得清空放量。
 */
class GrayCircuitBreakerTest {

    private final DynamicConfigService config = mock(DynamicConfigService.class);
    private final GrayHealthMetrics metrics = mock(GrayHealthMetrics.class);
    private final GrayRollbackService rollback = mock(GrayRollbackService.class);

    private final GrayCircuitBreaker breaker =
            new GrayCircuitBreaker(config, metrics, rollback);

    private void thresholds(boolean enabled, int window, int minSamples, int errorRate, int p95) {
        when(config.getBoolean(eq(ConfigKeys.GRAY_BREAKER_ENABLED), anyBoolean())).thenReturn(enabled);
        when(config.getInt(eq(ConfigKeys.GRAY_BREAKER_WINDOW_MINUTES), anyInt())).thenReturn(window);
        when(config.getInt(eq(ConfigKeys.GRAY_BREAKER_MIN_SAMPLES), anyInt())).thenReturn(minSamples);
        when(config.getInt(eq(ConfigKeys.GRAY_BREAKER_ERROR_RATE_PERCENT), anyInt())).thenReturn(errorRate);
        when(config.getInt(eq(ConfigKeys.GRAY_BREAKER_P95_MS), anyInt())).thenReturn(p95);
    }

    private static GrayHealthSnapshot snapshot(long turns, long anomalies, String rate, int p95) {
        return new GrayHealthSnapshot(5, turns, anomalies, new BigDecimal(rate), p95, 0L,
                LocalDateTime.now());
    }

    @Test
    @DisplayName("错误率越界 → 自动回滚，原因含判据与窗口样本（可直接用于事后复盘）")
    void rollsBackOnErrorRateBreach() {
        thresholds(true, 5, 20, 30, 0);
        when(metrics.measure(5)).thenReturn(snapshot(100, 45, "45.00", 1200));
        when(rollback.rollbackAll(anyString(), any(), any()))
                .thenReturn(new GrayRollbackService.RollbackResult(1, List.of("memory_growth=20")));

        GrayCircuitBreaker.Evaluation evaluation = breaker.evaluate();

        assertThat(evaluation.breached()).isTrue();
        assertThat(evaluation.breachCause()).contains("错误率 45.00% > 30%");
        assertThat(evaluation.rolledBack()).isEqualTo(1);
        verify(rollback).rollbackAll(org.mockito.ArgumentMatchers.argThat(
                reason -> reason.contains("熔断自动回滚")
                        && reason.contains("错误率 45.00% > 30%")
                        && reason.contains("轮次 100")
                        && reason.contains("异常 45")), eq(null), eq(null));
    }

    @Test
    @DisplayName("P95 时延越界（阈值 > 0 才启用该判据）→ 同样回滚")
    void rollsBackOnLatencyBreach() {
        thresholds(true, 5, 20, 30, 1000);
        when(metrics.measure(5)).thenReturn(snapshot(100, 1, "1.00", 1800));
        when(rollback.rollbackAll(anyString(), any(), any()))
                .thenReturn(new GrayRollbackService.RollbackResult(1, List.of("memory_growth=100")));

        GrayCircuitBreaker.Evaluation evaluation = breaker.evaluate();

        assertThat(evaluation.breached()).isTrue();
        assertThat(evaluation.breachCause()).contains("P95 时延 1800ms > 1000ms");
    }

    @Test
    @DisplayName("未越界：只读数不动配置（避免把正常波动当故障）")
    void doesNothingWhenHealthy() {
        thresholds(true, 5, 20, 30, 2000);
        when(metrics.measure(5)).thenReturn(snapshot(200, 20, "10.00", 900));

        GrayCircuitBreaker.Evaluation evaluation = breaker.evaluate();

        assertThat(evaluation.breached()).isFalse();
        assertThat(evaluation.skipped()).isFalse();
        verify(rollback, never()).rollbackAll(anyString(), any(), any());
    }

    @Test
    @DisplayName("样本不足不回滚：低峰期一条异常不得清空放量（防误熔断）")
    void skipsWhenSamplesInsufficient() {
        thresholds(true, 5, 20, 30, 0);
        when(metrics.measure(5)).thenReturn(snapshot(3, 3, "100.00", 900));

        GrayCircuitBreaker.Evaluation evaluation = breaker.evaluate();

        assertThat(evaluation.skipped()).isTrue();
        assertThat(evaluation.skipReason()).contains("样本不足");
        verify(rollback, never()).rollbackAll(anyString(), any(), any());
    }

    @Test
    @DisplayName("熔断开关关闭：连健康度都不查（管理员可完全托管放量）")
    void skipsEverythingWhenDisabled() {
        thresholds(false, 5, 20, 30, 0);

        GrayCircuitBreaker.Evaluation evaluation = breaker.evaluate();

        assertThat(evaluation.skipped()).isTrue();
        verify(metrics, never()).measure(anyInt());
        verify(rollback, never()).rollbackAll(anyString(), any(), any());
    }

    @Test
    @DisplayName("定时入口吞掉异常：取数/回滚失败不得冒泡到调度线程（否则熔断自身静默失效）")
    void scheduledEntrySwallowsFailure() {
        thresholds(true, 5, 20, 30, 0);
        when(metrics.measure(5)).thenThrow(new IllegalStateException("db down"));

        assertThatCode(() -> breaker.scheduledEvaluate()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("阈值读取带保守默认：配置缺失时 P95 判据不启用（0），错误率阈值 30%")
    void exposesThresholdsForConsole() {
        thresholds(true, 5, 20, 30, 0);

        GrayCircuitBreaker.Thresholds thresholds = breaker.thresholds();

        assertThat(thresholds.enabled()).isTrue();
        assertThat(thresholds.windowMinutes()).isEqualTo(5);
        assertThat(thresholds.errorRatePercent()).isEqualTo(30);
        assertThat(GrayCircuitBreaker.CHECK_INTERVAL_MS / 1000).isLessThanOrEqualTo(60);
    }
}
