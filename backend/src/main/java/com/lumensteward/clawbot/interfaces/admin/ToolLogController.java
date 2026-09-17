package com.lumensteward.clawbot.interfaces.admin;

import com.lumensteward.clawbot.application.admin.ToolLogQueryService;
import com.lumensteward.clawbot.common.api.ApiResponse;
import com.lumensteward.clawbot.common.api.PageResult;
import com.lumensteward.clawbot.common.error.ErrorCode;
import com.lumensteward.clawbot.common.exception.BizException;
import com.lumensteward.clawbot.infrastructure.persistence.entity.ToolCallLogEntity;
import com.lumensteward.clawbot.interfaces.assembler.MaskingAssembler;
import com.lumensteward.clawbot.interfaces.dto.toollog.ToolLogDetailVO;
import com.lumensteward.clawbot.interfaces.dto.toollog.ToolLogQuery;
import com.lumensteward.clawbot.interfaces.dto.toollog.ToolLogVO;
import com.lumensteward.clawbot.interfaces.dto.toollog.ToolStatsVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 工具调用日志控制器（架构 4.3 / GET /api/tool-logs、/{id}、/stats）。
 *
 * <p>全部角色可读；详情含入参/结果/耗时/降级原因（AC-E6）。数值口径为聚合，非估算（AC-E5）。
 */
@RestController
@RequestMapping("/api/tool-logs")
@Tag(name = "工具调用日志", description = "检索 / 详情 / 统计（只读）")
public class ToolLogController {

    private final ToolLogQueryService toolLogQueryService;
    private final MaskingAssembler maskingAssembler;

    /**
     * 构造器注入（G-14）。
     *
     * @param toolLogQueryService 工具日志查询服务
     * @param maskingAssembler    脱敏装配器
     */
    public ToolLogController(ToolLogQueryService toolLogQueryService,
                             MaskingAssembler maskingAssembler) {
        this.toolLogQueryService = toolLogQueryService;
        this.maskingAssembler = maskingAssembler;
    }

    /**
     * 分页检索工具调用日志。
     *
     * @param query 检索条件
     * @return 分页视图
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','OPERATOR','AUDITOR')")
    @Operation(summary = "工具日志检索", description = "支持 traceId/toolName/status/openid/时间范围")
    public ApiResponse<PageResult<ToolLogVO>> list(@Valid ToolLogQuery query) {
        PageResult<ToolCallLogEntity> page = toolLogQueryService.page(query, query.getTraceId(),
                query.getToolName(), query.getStatus(), query.getOpenid(),
                query.getStartTime(), query.getEndTime());
        return ApiResponse.success(
                maskingAssembler.assemblePage(page, maskingAssembler::toToolLogVO));
    }

    /**
     * 统计（调用量 / 成功率 / 耗时分布）。
     *
     * @param toolName  工具名过滤（可空）
     * @param startTime 下界（可空）
     * @param endTime   上界（可空）
     * @return 统计视图
     */
    @GetMapping("/stats")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','OPERATOR','AUDITOR')")
    @Operation(summary = "工具调用统计")
    public ApiResponse<ToolStatsVO> stats(@RequestParam(required = false) String toolName,
                                          @RequestParam(required = false)
                                          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                                          LocalDateTime startTime,
                                          @RequestParam(required = false)
                                          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                                          LocalDateTime endTime) {
        ToolLogQueryService.Stats stats = toolLogQueryService.stats(toolName, startTime, endTime);
        List<ToolStatsVO.ToolStatItem> items = stats.items().stream()
                .map(item -> new ToolStatsVO.ToolStatItem(item.toolName(), item.total(),
                        item.success(), item.failed(), item.degraded(), item.timeout(),
                        item.avgLatencyMs()))
                .toList();
        return ApiResponse.success(new ToolStatsVO(stats.total(), stats.success(), stats.failed(),
                stats.degraded(), stats.timeout(), stats.notExecuted(), stats.successRate(),
                stats.avgLatencyMs(), items));
    }

    /**
     * 日志详情。
     *
     * @param id 主键
     * @return 详情视图
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','OPERATOR','AUDITOR')")
    @Operation(summary = "工具日志详情", description = "含入参、结果、耗时与降级原因")
    public ApiResponse<ToolLogDetailVO> detail(@PathVariable Long id) {
        ToolCallLogEntity entity = toolLogQueryService.findById(id)
                .orElseThrow(() -> BizException.of(ErrorCode.RESOURCE_NOT_FOUND, "工具日志不存在"));
        return ApiResponse.success(maskingAssembler.toToolLogDetailVO(entity));
    }
}
