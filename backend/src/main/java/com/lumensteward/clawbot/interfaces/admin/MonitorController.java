package com.lumensteward.clawbot.interfaces.admin;

import com.lumensteward.clawbot.application.admin.MonitorService;
import com.lumensteward.clawbot.common.api.ApiResponse;
import com.lumensteward.clawbot.interfaces.dto.monitor.DegradeMetricsVO;
import com.lumensteward.clawbot.interfaces.dto.monitor.IntentSliceVO;
import com.lumensteward.clawbot.interfaces.dto.monitor.LatencyBucketVO;
import com.lumensteward.clawbot.interfaces.dto.monitor.MonitorOverviewVO;
import com.lumensteward.clawbot.interfaces.dto.monitor.SuccessRateVO;
import com.lumensteward.clawbot.interfaces.dto.monitor.TrendPointVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 会话与工具调用监控控制器（FR-17 / T4）与降级与拦截看板（A-3 / T5）。
 *
 * <p>只读监控（BR-23）：概览 KPI、趋势、成功率、意图分布、延迟分布、降级与拦截指标。
 * 会话列表/消息流、工具日志列表/详情/统计分别复用既有 {@code /api/sessions}、
 * {@code /api/tool-logs}，本控制器仅承载监控页专属的<b>聚合缺口</b>端点。
 *
 * <p>允许 SUPER_ADMIN / OPERATOR / AUDITOR 读取（与工具日志同口径，AC-E9）。
 */
@RestController
@RequestMapping("/api/monitor")
@Tag(name = "监控看板", description = "概览 / 趋势 / 成功率 / 意图 / 延迟 / 降级（只读，FR-17 / A-3）")
public class MonitorController {

    private final MonitorService monitorService;

    /**
     * 构造器注入（G-14）。
     *
     * @param monitorService 监控聚合服务
     */
    public MonitorController(MonitorService monitorService) {
        this.monitorService = monitorService;
    }

    /**
     * 概览 KPI。
     *
     * @return 概览视图
     */
    @GetMapping("/overview")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','OPERATOR','AUDITOR')")
    @Operation(summary = "监控概览", description = "今日消息 / 活跃用户 / 调用量 / 成功率 / 平均耗时 / 降级数")
    public ApiResponse<MonitorOverviewVO> overview() {
        MonitorService.Overview overview = monitorService.overview();
        return ApiResponse.success(new MonitorOverviewVO(overview.todayMessages(),
                overview.activeUsers(), overview.toolCalls(), overview.successRate(),
                overview.avgLatencyMs(), overview.degradedCount()));
    }

    /**
     * 工具调用趋势（折线）。
     *
     * @param start       下界（可空）
     * @param end         上界（可空）
     * @param granularity 粒度：{@code HOUR} 小时桶，其余按天桶（缺省按天）
     * @return 趋势点列表
     */
    @GetMapping("/trend")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','OPERATOR','AUDITOR')")
    @Operation(summary = "工具调用趋势", description = "按天/小时桶聚合的调用量与成功数")
    public ApiResponse<List<TrendPointVO>> trend(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime start,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime end,
            @RequestParam(required = false) String granularity) {
        List<TrendPointVO> points = monitorService.trend(start, end, granularity).stream()
                .map(point -> new TrendPointVO(point.bucket(), point.total(), point.success(),
                        point.successRate()))
                .toList();
        return ApiResponse.success(points);
    }

    /**
     * 按工具名分组的成功率（柱状）。
     *
     * @param start 下界（可空）
     * @param end   上界（可空）
     * @return 成功率列表
     */
    @GetMapping("/success-rate")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','OPERATOR','AUDITOR')")
    @Operation(summary = "工具成功率", description = "按工具名分组的成功数与成功率")
    public ApiResponse<List<SuccessRateVO>> successRate(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime start,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime end) {
        List<SuccessRateVO> items = monitorService.successRateByTool(start, end).stream()
                .map(item -> new SuccessRateVO(item.toolName(), item.total(), item.success(),
                        item.successRate()))
                .toList();
        return ApiResponse.success(items);
    }

    /**
     * 意图分布（饼图）。
     *
     * @param start 下界（可空）
     * @param end   上界（可空）
     * @return 意图分布列表
     */
    @GetMapping("/intent-distribution")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','OPERATOR','AUDITOR')")
    @Operation(summary = "意图分布", description = "按工具调用归因到业务意图域的分布")
    public ApiResponse<List<IntentSliceVO>> intentDistribution(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime start,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime end) {
        List<IntentSliceVO> slices = monitorService.intentDistribution(start, end).stream()
                .map(slice -> new IntentSliceVO(slice.intent(), slice.count()))
                .toList();
        return ApiResponse.success(slices);
    }

    /**
     * 延迟分布直方图。
     *
     * @param start 下界（可空）
     * @param end   上界（可空）
     * @return 延迟桶列表
     */
    @GetMapping("/latency-distribution")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','OPERATOR','AUDITOR')")
    @Operation(summary = "延迟分布", description = "固定桶的调用耗时直方分布")
    public ApiResponse<List<LatencyBucketVO>> latencyDistribution(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime start,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime end) {
        List<LatencyBucketVO> buckets = monitorService.latencyDistribution(start, end).stream()
                .map(bucket -> new LatencyBucketVO(bucket.range(), bucket.count()))
                .toList();
        return ApiResponse.success(buckets);
    }

    /**
     * 降级与拦截看板（A-3 / T5）。
     *
     * @param start 下界（可空）
     * @param end   上界（可空）
     * @return 降级与拦截指标
     */
    @GetMapping("/degrade")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','OPERATOR','AUDITOR')")
    @Operation(summary = "降级与拦截看板",
            description = "降级触发率 / 执行性幻觉拦截次数 / 四层异常分布 / 对外泄漏=0 KPI")
    public ApiResponse<DegradeMetricsVO> degrade(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime start,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime end) {
        MonitorService.DegradeMetrics metrics = monitorService.degradeMetrics(start, end);
        List<DegradeMetricsVO.AnomalyLayerVO> layers = metrics.anomalyDistribution().stream()
                .map(layer -> new DegradeMetricsVO.AnomalyLayerVO(layer.layer(), layer.label(),
                        layer.count()))
                .toList();
        return ApiResponse.success(new DegradeMetricsVO(metrics.totalCalls(),
                metrics.degradedCalls(), metrics.degradedCount(), metrics.timeoutCount(),
                metrics.degradeRate(), metrics.hallucinationInterceptions(),
                metrics.externalLeakCount(), metrics.leakKpiPass(), layers));
    }
}
