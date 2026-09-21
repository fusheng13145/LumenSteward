package com.lumensteward.clawbot.interfaces.admin;

import com.lumensteward.clawbot.application.gray.GrayCircuitBreaker;
import com.lumensteward.clawbot.application.gray.GrayFeature;
import com.lumensteward.clawbot.application.gray.GrayHealthMetrics;
import com.lumensteward.clawbot.application.gray.GrayHealthSnapshot;
import com.lumensteward.clawbot.application.gray.GrayReleaseService;
import com.lumensteward.clawbot.application.gray.GrayRollbackService;
import com.lumensteward.clawbot.common.api.ApiResponse;
import com.lumensteward.clawbot.common.exception.BizException;
import com.lumensteward.clawbot.infrastructure.security.AuthPrincipal;
import com.lumensteward.clawbot.interfaces.assembler.MaskingAssembler;
import com.lumensteward.clawbot.interfaces.dto.gray.GrayPreviewVO;
import com.lumensteward.clawbot.interfaces.dto.gray.GrayRollbackRequest;
import com.lumensteward.clawbot.interfaces.dto.gray.GrayRollbackVO;
import com.lumensteward.clawbot.interfaces.dto.gray.GrayStatusVO;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 灰度后台控制器测试（W2）。
 *
 * <p>用<b>真实</b> {@link MaskingAssembler}：预览出参的 openid 必须脱敏（BR-21），
 * 且状态视图只暴露白名单<b>人数</b>而非名单内容——这条只能靠真装配器证明。
 */
class GrayControllerTest {

    private final GrayReleaseService grayRelease = mock(GrayReleaseService.class);
    private final GrayCircuitBreaker circuitBreaker = mock(GrayCircuitBreaker.class);
    private final GrayHealthMetrics healthMetrics = mock(GrayHealthMetrics.class);
    private final GrayRollbackService rollbackService = mock(GrayRollbackService.class);

    private final GrayController controller = new GrayController(grayRelease, circuitBreaker,
            healthMetrics, rollbackService, new MaskingAssembler());

    @Test
    @DisplayName("状态视图：放量口径 + 熔断阈值 + 近窗健康度一次给全（后台无需二次拼接）")
    void shouldExposeStatus() {
        when(circuitBreaker.thresholds()).thenReturn(
                new GrayCircuitBreaker.Thresholds(true, 5, 20, 30, 0));
        when(healthMetrics.measure(5)).thenReturn(new GrayHealthSnapshot(5, 120L, 6L,
                new BigDecimal("5.00"), 880, 2L, LocalDateTime.now()));
        when(grayRelease.percent(GrayFeature.MEMORY_GROWTH)).thenReturn(20);
        when(grayRelease.whitelist(GrayFeature.MEMORY_GROWTH)).thenReturn(java.util.Set.of("a", "b"));

        ApiResponse<GrayStatusVO> response = controller.status();

        GrayStatusVO vo = response.getData();
        assertThat(vo).isNotNull();
        assertThat(vo.features()).hasSize(1);
        GrayStatusVO.Feature feature = vo.features().get(0);
        assertThat(feature.code()).isEqualTo("memory_growth");
        assertThat(feature.percent()).isEqualTo(20);
        assertThat(feature.whitelistSize()).isEqualTo(2);
        assertThat(feature.closed()).isFalse();
        assertThat(feature.fullyRolledOut()).isFalse();
        assertThat(vo.breaker().checkIntervalSecond()).isLessThanOrEqualTo(60);
        assertThat(vo.health().errorRatePercent()).isEqualByComparingTo("5.00");
        assertThat(vo.health().p95LatencyMs()).isEqualTo(880);
    }

    @Test
    @DisplayName("已回滚态：closed=true，后台一眼看出「当前不放量」而不是靠读数字推断")
    void shouldMarkClosedFeature() {
        when(circuitBreaker.thresholds()).thenReturn(
                new GrayCircuitBreaker.Thresholds(true, 5, 20, 30, 0));
        when(healthMetrics.measure(5)).thenReturn(GrayHealthSnapshot.empty(5));
        when(grayRelease.percent(GrayFeature.MEMORY_GROWTH)).thenReturn(0);
        when(grayRelease.whitelist(GrayFeature.MEMORY_GROWTH)).thenReturn(java.util.Set.of());

        GrayStatusVO.Feature feature = controller.status().getData().features().get(0);

        assertThat(feature.closed()).isTrue();
        assertThat(feature.percent()).isZero();
    }

    @Test
    @DisplayName("命中预览：出参 openid 脱敏（BR-21），分桶与原因原样透出")
    void shouldMaskOpenidInPreview() {
        when(grayRelease.decide(GrayFeature.MEMORY_GROWTH, "wx-openid-a1"))
                .thenReturn(new GrayReleaseService.Decision(true, 10, 3,
                        GrayReleaseService.Decision.PERCENT));

        ApiResponse<GrayPreviewVO> response = controller.preview("memory_growth", "wx-openid-a1");

        GrayPreviewVO vo = response.getData();
        assertThat(vo).isNotNull();
        assertThat(vo.openid()).isEqualTo("wx-o****d-a1");
        assertThat(vo.bucket()).isEqualTo(3);
        assertThat(vo.hit()).isTrue();
        assertThat(vo.reason()).isEqualTo("PERCENT");
    }

    @Test
    @DisplayName("未知灰度代号 → 可读的参数错误（不返回空视图让运维以为「没命中」）")
    void shouldRejectUnknownFeature() {
        assertThatThrownBy(() -> controller.preview("ghost", "wx-openid-a1"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("灰度代号不存在");
    }

    @Test
    @DisplayName("人工一键回滚：原因加前缀后透传操作人与来源 IP，结果原样出参")
    void shouldRollbackWithReasonAndOperator() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("10.0.0.1");
        when(rollbackService.rollbackAll(any(), any(), any())).thenReturn(
                new GrayRollbackService.RollbackResult(1, List.of("memory_growth=20")));

        ApiResponse<GrayRollbackVO> response = controller.rollback(
                new GrayRollbackRequest("放量后异常升高"),
                new AuthPrincipal(2L, "root", "SUPER_ADMIN", "超管", "jti"), request);

        verify(rollbackService).rollbackAll(eq("人工回滚：放量后异常升高"), eq(2L), eq("10.0.0.1"));
        assertThat(response.getData()).isNotNull();
        assertThat(response.getData().rolledBack()).isEqualTo(1);
        assertThat(response.getData().fromPercent()).containsExactly("memory_growth=20");
    }
}
