package com.lumensteward.clawbot.interfaces.admin;

import com.lumensteward.clawbot.application.admin.AuditLogQueryService;
import com.lumensteward.clawbot.common.api.ApiResponse;
import com.lumensteward.clawbot.common.api.PageQuery;
import com.lumensteward.clawbot.common.api.PageResult;
import com.lumensteward.clawbot.common.util.MaskUtils;
import com.lumensteward.clawbot.infrastructure.persistence.entity.AuditLogEntity;
import com.lumensteward.clawbot.interfaces.assembler.MaskingAssembler;
import com.lumensteward.clawbot.interfaces.dto.audit.AuditLogVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 档案变更留痕控制器（A-4 / T7，迭代 3 Wave 2 低风险早期胜利）。
 *
 * <p>复用 FR-16 审计范式：档案写操作经 {@code PetProfileServiceImpl} 记入 {@code log_audit}
 * （{@code reg_type=PROFILE}，前后值为<b>字段级 diff</b> 而非整对象 JSON）。本控制器仅做<b>只读检索</b>，
 * 固定过滤 {@code reg_type=PROFILE}，经 {@link MaskingAssembler} 装配 {@link AuditLogVO}
 * （{@code beforeValue}/{@code afterValue} 已是字段级 diff JSON，前端解析展示）。
 *
 * <p>允许 SUPER_ADMIN / OPERATOR / AUDITOR 读取（与工具日志同口径，AC-E9）。
 */
@RestController
@RequestMapping("/api/profiles/history")
@Tag(name = "档案变更留痕", description = "档案字段级变更历史（OPERATOR/AUDITOR+ 只读，A-4 / T7）")
public class ProfileHistoryController {

    private static final String REG_TYPE_PROFILE = "PROFILE";

    private final AuditLogQueryService auditLogQueryService;
    private final MaskingAssembler maskingAssembler;

    /**
     * 构造器注入（G-14）。
     *
     * @param auditLogQueryService 审计查询服务
     * @param maskingAssembler     脱敏装配器
     */
    public ProfileHistoryController(AuditLogQueryService auditLogQueryService,
                                    MaskingAssembler maskingAssembler) {
        this.auditLogQueryService = auditLogQueryService;
        this.maskingAssembler = maskingAssembler;
    }

    /**
     * 分页检索档案变更历史。
     *
     * <p>{@code openid} 经 {@link MaskUtils#openid(String)} 脱敏后匹配 {@code target} 列（空白输入则不过滤）；
     * {@code action} 取 CREATE/UPDATE/DELETE 之一。两者皆空时返回全部档案变更。
     *
     * @param query   分页参数
     * @param openid  用户标识（可空，脱敏后精确匹配）
     * @param action  操作标识（可空）
     * @return 分页视图（beforeValue/afterValue 为字段级 diff JSON）
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','OPERATOR','AUDITOR')")
    @Operation(summary = "档案变更历史", description = "按 openid（脱敏匹配）/action 检索 reg_type=PROFILE 的字段级变更")
    public ApiResponse<PageResult<AuditLogVO>> list(@Valid PageQuery query,
                                                    @RequestParam(required = false) String openid,
                                                    @RequestParam(required = false) String action) {
        String target = MaskUtils.openid(openid);
        PageResult<AuditLogEntity> page = auditLogQueryService.page(query, REG_TYPE_PROFILE, action,
                target, null, null);
        return ApiResponse.success(
                maskingAssembler.assemblePage(page, maskingAssembler::toAuditLogVO));
    }
}
