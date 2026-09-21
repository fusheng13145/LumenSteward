package com.lumensteward.clawbot.interfaces.admin;

import com.lumensteward.clawbot.application.admin.CostService;
import com.lumensteward.clawbot.common.api.ApiResponse;
import com.lumensteward.clawbot.interfaces.dto.cost.CostVO;
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
 * 成本与配额治理看板控制器（B-4 / 迭代 4 W5，FR-17 只读 + FR-20 ③ 配额可视化）。
 *
 * <p>全部只读（BR-23）：SUPER_ADMIN / OPERATOR / AUDITOR 可读，与监控、状态库列表同口径（AC-E9）。
 * 调整阈值<b>不在本控制器</b>——预算与消息长度走既有的 {@code /api/configs}（FR-18 免重启生效），
 * 读写分离使「谁改了预算」必然留在 {@code log_audit}。
 *
 * <p><b>出参不再脱敏</b>：{@code log_llm_call.openid} 在<b>写入侧</b>已集中脱敏（BR-21），
 * 此处若再过一遍 {@code MaskUtils} 会把已打码的串二次削尾，产出错误标识。
 */
@RestController
@RequestMapping("/api/cost")
@Tag(name = "成本与配额看板", description = "token 计量 / 预算消耗 / 用途与模型分布（只读，B-4）")
public class CostController {

    private final CostService service;

    /**
     * 构造器注入（G-14）。
     *
     * @param service 成本聚合服务
     */
    public CostController(CostService service) {
        this.service = service;
    }

    /**
     * 概览 KPI：今日消耗、生效预算、两套百分比与降级状态。
     *
     * @return 概览视图
     */
    @GetMapping("/overview")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','OPERATOR','AUDITOR')")
    @Operation(summary = "成本概览",
            description = "今日 token / 生效日预算 / 已用百分比（DB 明细与 Redis 计数双口径）/ 是否降级")
    public ApiResponse<CostVO.OverviewVO> overview() {
        CostService.Overview overview = service.overview();
        return ApiResponse.success(new CostVO.OverviewVO(overview.todayCalls(),
                overview.todayPromptTokens(), overview.todayCompletionTokens(), overview.todayTokens(),
                overview.dailyBudget(), overview.usedPercent(), overview.status(),
                overview.counterUsagePercent(), overview.degraded(), overview.monthTokens()));
    }

    /**
     * token 消耗趋势（折线）。
     *
     * @param start       下界（可空）
     * @param end         上界（可空）
     * @param granularity 粒度：{@code HOUR} 小时桶，其余按天桶
     * @return 趋势点列表
     */
    @GetMapping("/trend")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','OPERATOR','AUDITOR')")
    @Operation(summary = "消耗趋势", description = "按天/小时桶聚合的调用量与 prompt/completion/total token")
    public ApiResponse<List<CostVO.TrendPointVO>> trend(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime start,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime end,
            @RequestParam(required = false) String granularity) {
        List<CostVO.TrendPointVO> points = service.trend(start, end, granularity).stream()
                .map(point -> new CostVO.TrendPointVO(point.bucket(), point.calls(), point.promptTokens(),
                        point.completionTokens(), point.tokens()))
                .toList();
        return ApiResponse.success(points);
    }

    /**
     * 按用途分布（饼图）。
     *
     * @param start 下界（可空）
     * @param end   上界（可空）
     * @return 分布列表
     */
    @GetMapping("/by-purpose")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','OPERATOR','AUDITOR')")
    @Operation(summary = "按用途分布", description = "对话编排 / 状态库抽取 / 意图分类三类调用的 token 占比")
    public ApiResponse<List<CostVO.PurposeSliceVO>> byPurpose(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime start,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime end) {
        List<CostVO.PurposeSliceVO> slices = service.byPurpose(start, end).stream()
                .map(slice -> new CostVO.PurposeSliceVO(slice.purpose(), slice.calls(), slice.tokens(),
                        slice.percent()))
                .toList();
        return ApiResponse.success(slices);
    }

    /**
     * 按供应商/模型分布（柱状）。
     *
     * @param start 下界（可空）
     * @param end   上界（可空）
     * @return 分布列表（按 token 降序）
     */
    @GetMapping("/by-model")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','OPERATOR','AUDITOR')")
    @Operation(summary = "按模型分布", description = "provider + model 分组的调用量与 token 占比")
    public ApiResponse<List<CostVO.ModelSliceVO>> byModel(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime start,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime end) {
        List<CostVO.ModelSliceVO> slices = service.byModel(start, end).stream()
                .map(slice -> new CostVO.ModelSliceVO(slice.provider(), slice.model(), slice.calls(),
                        slice.tokens(), slice.percent()))
                .toList();
        return ApiResponse.success(slices);
    }

    /**
     * 用户消耗排行（脱敏标识）。
     *
     * @param start 下界（可空）
     * @param end   上界（可空）
     * @param limit 条数（缺省 10，上限 50）
     * @return 排行列表
     */
    @GetMapping("/top-users")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','OPERATOR','AUDITOR')")
    @Operation(summary = "用户消耗排行", description = "按脱敏 openid 分组的 token 消耗降序排行")
    public ApiResponse<List<CostVO.UserSliceVO>> topUsers(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime start,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime end,
            @RequestParam(required = false) Integer limit) {
        List<CostVO.UserSliceVO> slices = service.topUsers(start, end, limit).stream()
                .map(slice -> new CostVO.UserSliceVO(slice.openid(), slice.calls(), slice.tokens()))
                .toList();
        return ApiResponse.success(slices);
    }

    /**
     * 明细抽样：看板上每个聚合值都应能在本端点里找到对应行（FR-17 AC① 的取证入口）。
     *
     * @param start 下界（可空）
     * @param end   上界（可空）
     * @param limit 条数（缺省 10，上限 50）
     * @return 明细行（按 id 倒序）
     */
    @GetMapping("/recent")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','OPERATOR','AUDITOR')")
    @Operation(summary = "调用明细抽样", description = "log_llm_call 逐行明细，用于核对聚合数值与直接 SQL 一致")
    public ApiResponse<List<CostVO.CallDetailVO>> recent(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime start,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime end,
            @RequestParam(required = false) Integer limit) {
        List<CostVO.CallDetailVO> details = service.recentCalls(start, end, limit).stream()
                .map(detail -> new CostVO.CallDetailVO(detail.id(), detail.purpose(), detail.provider(),
                        detail.model(), detail.openid(), detail.sessionId(), detail.traceId(),
                        detail.promptTokens(), detail.completionTokens(), detail.totalTokens(),
                        detail.createdAt()))
                .toList();
        return ApiResponse.success(details);
    }
}
