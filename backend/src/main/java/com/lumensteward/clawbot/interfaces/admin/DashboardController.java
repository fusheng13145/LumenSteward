package com.lumensteward.clawbot.interfaces.admin;

import com.lumensteward.clawbot.application.admin.DashboardService;
import com.lumensteward.clawbot.common.api.ApiResponse;
import com.lumensteward.clawbot.interfaces.dto.dashboard.DashboardSummaryVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 概览看板控制器（架构 4.3 / GET /api/dashboard/summary，AC-E5）。
 *
 * <p>全部指标由 COUNT 聚合直接产出，可用等价 SQL 复核（非估算）。
 */
@RestController
@RequestMapping("/api/dashboard")
@Tag(name = "概览看板", description = "今日消息 / 活跃用户 / 工具调用 / 成功率 / 降级数")
public class DashboardController {

    private final DashboardService dashboardService;

    /**
     * 构造器注入（G-14）。
     *
     * @param dashboardService 看板服务
     */
    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    /**
     * 看板汇总。
     *
     * @return 汇总视图
     */
    @GetMapping("/summary")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','OPERATOR','AUDITOR')")
    @Operation(summary = "看板汇总", description = "数值可由 SQL 直接复核")
    public ApiResponse<DashboardSummaryVO> summary() {
        DashboardService.Summary summary = dashboardService.summary();
        return ApiResponse.success(new DashboardSummaryVO(
                summary.todayMessages(), summary.activeUsers(), summary.toolCalls(),
                summary.toolSuccess(), summary.toolFailed(), summary.toolDegraded(),
                summary.toolTimeout(), summary.successRate(), summary.degradedCount()));
    }
}
