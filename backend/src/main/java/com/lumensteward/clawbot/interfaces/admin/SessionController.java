package com.lumensteward.clawbot.interfaces.admin;

import com.lumensteward.clawbot.application.admin.SessionQueryService;
import com.lumensteward.clawbot.common.api.ApiResponse;
import com.lumensteward.clawbot.common.api.PageQuery;
import com.lumensteward.clawbot.common.api.PageResult;
import com.lumensteward.clawbot.infrastructure.persistence.entity.WxMessageEntity;
import com.lumensteward.clawbot.infrastructure.persistence.entity.WxSessionEntity;
import com.lumensteward.clawbot.interfaces.assembler.MaskingAssembler;
import com.lumensteward.clawbot.interfaces.dto.session.MessageVO;
import com.lumensteward.clawbot.interfaces.dto.session.SessionVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 会话与监控控制器（架构 4.3 / GET /api/sessions、/api/sessions/{id}/messages）。
 *
 * <p>全部角色可读（AC-E9：AUDITOR 只读接口可用）。
 */
@RestController
@RequestMapping("/api/sessions")
@Tag(name = "会话监控", description = "会话列表与消息流（只读）")
public class SessionController {

    private final SessionQueryService sessionQueryService;
    private final MaskingAssembler maskingAssembler;

    /**
     * 构造器注入（G-14）。
     *
     * @param sessionQueryService 会话查询服务
     * @param maskingAssembler    脱敏装配器
     */
    public SessionController(SessionQueryService sessionQueryService,
                             MaskingAssembler maskingAssembler) {
        this.sessionQueryService = sessionQueryService;
        this.maskingAssembler = maskingAssembler;
    }

    /**
     * 分页查询会话（按 last_active_at 倒序）。
     *
     * @param query  分页参数
     * @param openid 用户过滤（可空）
     * @param state  状态过滤（可空）
     * @return 分页视图
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','OPERATOR','AUDITOR')")
    @Operation(summary = "会话列表")
    public ApiResponse<PageResult<SessionVO>> list(@Valid PageQuery query,
                                                   @RequestParam(required = false) String openid,
                                                   @RequestParam(required = false) String state) {
        PageResult<WxSessionEntity> page = sessionQueryService.page(query, openid, state);
        return ApiResponse.success(
                maskingAssembler.assemblePage(page, maskingAssembler::toSessionVO));
    }

    /**
     * 会话消息流。
     *
     * @param id      会话主键
     * @param role    角色过滤（可空）
     * @param msgType 消息类型过滤（可空）
     * @return 消息列表
     */
    @GetMapping("/{id}/messages")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','OPERATOR','AUDITOR')")
    @Operation(summary = "会话消息流")
    public ApiResponse<List<MessageVO>> messages(@PathVariable Long id,
                                                 @RequestParam(required = false) String role,
                                                 @RequestParam(required = false) String msgType) {
        List<WxMessageEntity> messages = sessionQueryService.messages(id, role, msgType);
        return ApiResponse.success(maskingAssembler.mapList(messages, maskingAssembler::toMessageVO));
    }
}
