package com.lumensteward.clawbot.interfaces.admin;

import com.lumensteward.clawbot.application.admin.MemoryAdminService;
import com.lumensteward.clawbot.common.api.ApiResponse;
import com.lumensteward.clawbot.common.api.PageQuery;
import com.lumensteward.clawbot.common.api.PageResult;
import com.lumensteward.clawbot.infrastructure.persistence.entity.MemoryItemEntity;
import com.lumensteward.clawbot.infrastructure.security.AuthPrincipal;
import com.lumensteward.clawbot.interfaces.assembler.MaskingAssembler;
import com.lumensteward.clawbot.interfaces.dto.memory.MemoryItemVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 个人状态库后台控制器（迭代 4 W6-b / §2.19）。
 *
 * <p>列表为只读（BR-23 口径同监控：SUPER_ADMIN / OPERATOR / AUDITOR 可读，AC-E9）；
 * 删除是<b>人工纠错</b>入口，仅 SUPER_ADMIN（与合规报告同为治理侧动作）。
 * 出参 openid 一律经 {@link MaskingAssembler} 脱敏（BR-21）。
 */
@RestController
@RequestMapping("/api/memories")
@Tag(name = "个人状态库（后台）", description = "条目只读查询与纠错删除（W6-b）")
public class MemoryController {

    private final MemoryAdminService memoryAdminService;
    private final MaskingAssembler maskingAssembler;

    /**
     * 构造器注入（G-14）。
     *
     * @param memoryAdminService 状态库后台服务
     * @param maskingAssembler   脱敏装配器
     */
    public MemoryController(MemoryAdminService memoryAdminService,
                            MaskingAssembler maskingAssembler) {
        this.memoryAdminService = memoryAdminService;
        this.maskingAssembler = maskingAssembler;
    }

    /**
     * 分页查询状态库条目（按最近出现时间倒序）。
     *
     * @param query  分页参数
     * @param openid 用户过滤（可空）
     * @param kind   条目类型过滤（可空）
     * @param status 状态过滤（可空：ACTIVE / SUPERSEDED）
     * @return 分页视图
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','OPERATOR','AUDITOR')")
    @Operation(summary = "状态库列表", description = "只读分页查询，出参 openid 已脱敏")
    public ApiResponse<PageResult<MemoryItemVO>> list(@Valid PageQuery query,
                                                      @RequestParam(required = false) String openid,
                                                      @RequestParam(required = false) String kind,
                                                      @RequestParam(required = false) String status) {
        PageResult<MemoryItemEntity> page = memoryAdminService.page(query, openid, kind, status);
        return ApiResponse.success(
                maskingAssembler.assemblePage(page, maskingAssembler::toMemoryItemVO));
    }

    /**
     * 纠错删除一条条目（逻辑删除，留审计）。
     *
     * @param id        条目主键
     * @param principal 当前主体
     * @param request   HTTP 请求（取来源 IP）
     * @return 空响应
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "纠错删除条目", description = "逻辑删除并写 log_audit（reg_type=MEMORY）")
    public ApiResponse<Void> delete(@PathVariable Long id,
                                    @AuthenticationPrincipal AuthPrincipal principal,
                                    HttpServletRequest request) {
        memoryAdminService.delete(id, principal == null ? null : principal.adminId(),
                ClientIp.of(request));
        return ApiResponse.success();
    }
}
