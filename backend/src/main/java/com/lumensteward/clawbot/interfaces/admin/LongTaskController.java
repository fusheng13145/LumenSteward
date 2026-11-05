package com.lumensteward.clawbot.interfaces.admin;

import com.lumensteward.clawbot.application.task.LongTaskService;
import com.lumensteward.clawbot.common.api.ApiResponse;
import com.lumensteward.clawbot.common.error.ErrorCode;
import com.lumensteward.clawbot.common.exception.BizException;
import com.lumensteward.clawbot.common.util.MaskUtils;
import com.lumensteward.clawbot.infrastructure.persistence.service.AuditLogService;
import com.lumensteward.clawbot.infrastructure.security.AuthPrincipal;
import com.lumensteward.clawbot.interfaces.assembler.MaskingAssembler;
import com.lumensteward.clawbot.interfaces.dto.task.LongTaskVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 长任务管理端点（迭代 4 W15）：SUPER_ADMIN 独占的通道控制面——提交（运维补跑/联调）
 * 与按 id 查询。用户侧入口随 W14 生图接入（工具内异步提交）。
 *
 * <p>审计：提交动作落 {@code log_audit(LONG_TASK/SUBMIT)}，载荷仅前 200 字（防大段粘贴入审计）。
 */
@RestController
@RequestMapping("/api/long-tasks")
@Tag(name = "长任务通道", description = "W15：任务提交与查询（SUPER_ADMIN）")
public class LongTaskController {

    private static final int AUDIT_PAYLOAD_LIMIT = 200;

    private final LongTaskService longTaskService;
    private final MaskingAssembler maskingAssembler;
    private final AuditLogService auditLogService;

    /** 构造器注入（G-14）。 */
    public LongTaskController(LongTaskService longTaskService, MaskingAssembler maskingAssembler,
                              AuditLogService auditLogService) {
        this.longTaskService = longTaskService;
        this.maskingAssembler = maskingAssembler;
        this.auditLogService = auditLogService;
    }

    /**
     * 提交长任务。
     *
     * @param body      {@code {type, openid, payload}}（payload 为 JSON 文本，可空）
     * @param principal 管理主体
     * @param request   客户端请求（取 IP 审计）
     * @return 任务 id
     */
    @PostMapping
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "提交长任务", description = "type 须为已注册类型；payload 为 JSON 文本（可空）")
    public ResponseEntity<ApiResponse<Map<String, Object>>> submit(
            @RequestBody Map<String, String> body,
            @AuthenticationPrincipal AuthPrincipal principal,
            HttpServletRequest request) {
        String type = body.get("type");
        String openid = body.get("openid");
        String payload = body.get("payload");
        if (openid == null || openid.isBlank()) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "openid 不能为空");
        }
        long id = longTaskService.submit(type, openid.trim(), payload, traceId(request));
        auditLogService.record(principal.adminId(), "LONG_TASK", "SUBMIT",
                MaskUtils.openid(openid), "type=" + type + " payload="
                        + truncate(payload), null, "提交长任务", ClientIp.of(request), 1);
        return ResponseEntity.ok(ApiResponse.success(Map.of("id", id)));
    }

    /** 按 id 查询（openid 脱敏出参）。 */
    @GetMapping("/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "查询长任务")
    public ResponseEntity<ApiResponse<LongTaskVO>> get(@PathVariable long id) {
        return longTaskService.get(id)
                .map(maskingAssembler::toLongTaskVO)
                .map(vo -> ResponseEntity.ok(ApiResponse.success(vo)))
                .orElseThrow(() -> BizException.of(ErrorCode.RESOURCE_NOT_FOUND, "长任务不存在: " + id));
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= AUDIT_PAYLOAD_LIMIT ? value : value.substring(0, AUDIT_PAYLOAD_LIMIT);
    }

    private static String traceId(HttpServletRequest request) {
        return request == null ? null : request.getHeader("X-Trace-Id");
    }
}
