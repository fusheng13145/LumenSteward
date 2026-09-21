package com.lumensteward.clawbot.interfaces.admin;

import com.lumensteward.clawbot.application.gray.GrayCircuitBreaker;
import com.lumensteward.clawbot.application.gray.GrayFeature;
import com.lumensteward.clawbot.application.gray.GrayHealthMetrics;
import com.lumensteward.clawbot.application.gray.GrayHealthSnapshot;
import com.lumensteward.clawbot.application.gray.GrayReleaseService;
import com.lumensteward.clawbot.application.gray.GrayRollbackService;
import com.lumensteward.clawbot.common.api.ApiResponse;
import com.lumensteward.clawbot.common.error.ErrorCode;
import com.lumensteward.clawbot.common.exception.BizException;
import com.lumensteward.clawbot.infrastructure.security.AuthPrincipal;
import com.lumensteward.clawbot.interfaces.assembler.MaskingAssembler;
import com.lumensteward.clawbot.interfaces.dto.gray.GrayPreviewVO;
import com.lumensteward.clawbot.interfaces.dto.gray.GrayRollbackRequest;
import com.lumensteward.clawbot.interfaces.dto.gray.GrayRollbackVO;
import com.lumensteward.clawbot.interfaces.dto.gray.GrayStatusVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * 灰度开关与熔断回滚控制器（FR-22 / 迭代 4 W2）。
 *
 * <p>读口径与监控一致（BR-23：SUPER_ADMIN / OPERATOR / AUDITOR 可读，AC-E9）；
 * <b>一键回滚</b>属治理侧写动作，仅 SUPER_ADMIN（与合规报告、状态库纠错同口径）。
 *
 * <p>本控制器<b>不提供改比例的端点</b>——比例与白名单就是 {@code sys_config} 的两项配置，
 * 写入一律走 {@code PUT /api/configs}（FR-18 的校验、热生效、逐项审计一次都不绕过）。
 * 出参只出现脱敏 openid 与白名单<b>人数</b>，白名单明文不经任何端点外泄（BR-21）。
 */
@RestController
@RequestMapping("/api/gray")
@Tag(name = "灰度开关（后台）", description = "放量口径查看 / 单用户命中自证 / 一键回滚（FR-22）")
public class GrayController {

    private final GrayReleaseService grayRelease;
    private final GrayCircuitBreaker circuitBreaker;
    private final GrayHealthMetrics healthMetrics;
    private final GrayRollbackService rollbackService;
    private final MaskingAssembler maskingAssembler;

    /**
     * 构造器注入（G-14）。
     *
     * @param grayRelease     灰度分流引擎
     * @param circuitBreaker  熔断守护（读阈值 + 手动触发）
     * @param healthMetrics   熔断所依据的近窗健康度
     * @param rollbackService 一键回滚
     * @param maskingAssembler 脱敏装配器
     */
    public GrayController(GrayReleaseService grayRelease,
                          GrayCircuitBreaker circuitBreaker,
                          GrayHealthMetrics healthMetrics,
                          GrayRollbackService rollbackService,
                          MaskingAssembler maskingAssembler) {
        this.grayRelease = grayRelease;
        this.circuitBreaker = circuitBreaker;
        this.healthMetrics = healthMetrics;
        this.rollbackService = rollbackService;
        this.maskingAssembler = maskingAssembler;
    }

    /**
     * 灰度现状：各功能放量口径 + 熔断阈值 + 近窗健康度（与熔断器同一取数口径）。
     *
     * @return 状态视图
     */
    @GetMapping("/status")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','OPERATOR','AUDITOR')")
    @Operation(summary = "灰度现状", description = "只读：放量比例、白名单人数、熔断阈值与窗口健康度")
    public ApiResponse<GrayStatusVO> status() {
        GrayCircuitBreaker.Thresholds thresholds = circuitBreaker.thresholds();
        GrayHealthSnapshot health = healthMetrics.measure(thresholds.windowMinutes());
        List<GrayStatusVO.Feature> features = new ArrayList<>();
        for (GrayFeature feature : GrayFeature.values()) {
            int percent = grayRelease.percent(feature);
            features.add(new GrayStatusVO.Feature(feature.code(), feature.label(), percent,
                    grayRelease.whitelist(feature).size(), percent >= 100, percent <= 0));
        }
        return ApiResponse.success(new GrayStatusVO(features,
                new GrayStatusVO.Breaker(thresholds.enabled(), thresholds.windowMinutes(),
                        thresholds.minSamples(), thresholds.errorRatePercent(), thresholds.p95Ms(),
                        GrayCircuitBreaker.CHECK_INTERVAL_MS / 1000),
                new GrayStatusVO.Health(health.windowMinutes(), health.turns(), health.anomalies(),
                        health.errorRatePercent(), health.p95LatencyMs(), health.exceededBudget(),
                        health.sampledAt())));
    }

    /**
     * 单用户命中自证：给出分桶与判定原因（出参 openid 已脱敏）。
     *
     * @param feature 灰度代号
     * @param openid  用户标识（原始值，仅参与判定与脱敏回显）
     * @return 判定明细
     */
    @GetMapping("/preview")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','OPERATOR','AUDITOR')")
    @Operation(summary = "命中预览", description = "同一入参必得同一结果，用于核对放量比例与白名单")
    public ApiResponse<GrayPreviewVO> preview(@RequestParam String feature,
                                              @RequestParam String openid) {
        GrayFeature grayFeature = GrayFeature.fromCode(feature);
        if (grayFeature == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "灰度代号不存在: " + feature);
        }
        return ApiResponse.success(maskingAssembler.toGrayPreviewVO(grayFeature, openid,
                grayRelease.decide(grayFeature, openid)));
    }

    /**
     * 一键回滚：把全部在放量的灰度比例置 0（含白名单一并失效）。
     *
     * @param request   回滚原因（必填，BR-25）
     * @param principal 当前主体
     * @param httpRequest HTTP 请求（取来源 IP）
     * @return 回滚结果
     */
    @PostMapping("/rollback")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "一键回滚", description = "比例置 0 并即时生效（免重启），写 reg_type=GRAY 审计")
    public ApiResponse<GrayRollbackVO> rollback(@Valid @RequestBody GrayRollbackRequest request,
                                                @AuthenticationPrincipal AuthPrincipal principal,
                                                HttpServletRequest httpRequest) {
        GrayRollbackService.RollbackResult result = rollbackService.rollbackAll(
                "人工回滚：" + request.reason(),
                principal == null ? null : principal.adminId(), ClientIp.of(httpRequest));
        return ApiResponse.success(new GrayRollbackVO(result.rolledBack(), result.fromPercent()));
    }
}
