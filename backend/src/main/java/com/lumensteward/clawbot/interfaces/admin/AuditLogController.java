package com.lumensteward.clawbot.interfaces.admin;

import com.lumensteward.clawbot.application.admin.AuditLogQueryService;
import com.lumensteward.clawbot.common.api.ApiResponse;
import com.lumensteward.clawbot.common.api.PageQuery;
import com.lumensteward.clawbot.common.api.PageResult;
import com.lumensteward.clawbot.infrastructure.persistence.entity.AuditLogEntity;
import com.lumensteward.clawbot.interfaces.assembler.MaskingAssembler;
import com.lumensteward.clawbot.interfaces.dto.audit.AuditLogVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

/**
 * 审计日志控制器（架构 4.3 / GET /api/audit-logs，AUDITOR+）。
 *
 * <p>允许 SUPER_ADMIN 与 AUDITOR 读取（AC-E9）。
 */
@RestController
@RequestMapping("/api/audit-logs")
@Tag(name = "审计日志", description = "审计检索（AUDITOR+ 只读）")
public class AuditLogController {

    private final AuditLogQueryService auditLogQueryService;
    private final MaskingAssembler maskingAssembler;

    /**
     * 构造器注入（G-14）。
     *
     * @param auditLogQueryService 审计查询服务
     * @param maskingAssembler     脱敏装配器
     */
    public AuditLogController(AuditLogQueryService auditLogQueryService,
                              MaskingAssembler maskingAssembler) {
        this.auditLogQueryService = auditLogQueryService;
        this.maskingAssembler = maskingAssembler;
    }

    /**
     * 分页检索审计日志。
     *
     * @param query     分页参数
     * @param regType   资源类型（可空）
     * @param action    操作标识（可空）
     * @param startTime 下界（可空）
     * @param endTime   上界（可空）
     * @return 分页视图
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','AUDITOR')")
    @Operation(summary = "审计日志检索", description = "支持 regType/action/时间范围")
    public ApiResponse<PageResult<AuditLogVO>> list(@Valid PageQuery query,
                                                    @RequestParam(required = false) String regType,
                                                    @RequestParam(required = false) String action,
                                                    @RequestParam(required = false)
                                                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                                                    LocalDateTime startTime,
                                                    @RequestParam(required = false)
                                                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                                                    LocalDateTime endTime) {
        PageResult<AuditLogEntity> page = auditLogQueryService.page(query, regType, action,
                startTime, endTime);
        return ApiResponse.success(
                maskingAssembler.assemblePage(page, maskingAssembler::toAuditLogVO));
    }
}
