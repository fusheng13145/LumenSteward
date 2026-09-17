package com.lumensteward.clawbot.infrastructure.security;

import com.lumensteward.clawbot.common.api.ApiResponse;
import com.lumensteward.clawbot.common.error.ErrorCode;
import com.lumensteward.clawbot.common.util.JsonUtils;
import com.lumensteward.clawbot.infrastructure.persistence.service.AuditLogService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 越权访问处理器（G-08 / AC-E3 / NFR-SE-05）。
 *
 * <p>已认证但权限不足（如 OPERATOR 调用配置写接口、AUDITOR 调用任何写接口）时返回
 * <b>HTTP 403</b> + {@code code=20003}，供前端区分「未登录」与「无权限」。
 *
 * <p><b>D5 修复（越权审计）：</b>每次越权尝试除返回 403 外，同步写入一条 {@code audit_log}
 * （{@code reg_type=AUTH}、{@code action=ACCESS_DENIED}、{@code result=0}），记录操作人、目标 URI、
 * 来源 IP，形成可追溯的越权审计链（NFR-SE-05）。审计写入为 best-effort：任何失败仅记日志，
 * <b>绝不影响</b> 403 响应（安全通道不得因审计故障而放行或 500）。
 *
 * <p>保留无参构造以便独立构造（无 Spring 上下文 / 单元测试）；此时审计跳过。
 */
@Component
public class RestAccessDeniedHandler implements AccessDeniedHandler {

    private static final Logger log = LoggerFactory.getLogger(RestAccessDeniedHandler.class);

    /** 审计资源类型：认证/授权域。 */
    private static final String REG_TYPE_AUTH = "AUTH";

    /** 审计操作：越权访问被拒绝。 */
    private static final String ACTION_ACCESS_DENIED = "ACCESS_DENIED";

    private final AuditLogService auditLogService;

    /**
     * Spring 装配用构造器（G-14）。
     *
     * @param auditLogService 审计日志服务
     */
    @Autowired
    public RestAccessDeniedHandler(AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    /**
     * 独立构造（无审计）：保留以便脱离 Spring 上下文的单元构造；此时仅返回 403、不写审计。
     */
    public RestAccessDeniedHandler() {
        this.auditLogService = null;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        audit(request);
        response.setStatus(ErrorCode.FORBIDDEN.getHttpStatus());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(JsonUtils.toJson(ApiResponse.error(ErrorCode.FORBIDDEN)));
    }

    /**
     * 写入越权审计（best-effort，绝不抛出）。
     *
     * @param request 被拒绝的请求
     */
    private void audit(HttpServletRequest request) {
        if (auditLogService == null) {
            return;
        }
        try {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            Long adminId = null;
            if (authentication != null && authentication.getPrincipal() instanceof AuthPrincipal principal) {
                adminId = principal.adminId();
            }
            String target = request == null ? null : request.getRequestURI();
            auditLogService.record(adminId, REG_TYPE_AUTH, ACTION_ACCESS_DENIED, target,
                    null, null, "越权访问被拒绝", clientIp(request), 0);
        } catch (RuntimeException e) {
            // D7：不再静默——ERROR 级并显式标注表名，确保越权审计失败可被观测（403 响应不受影响）。
            log.error("越权审计写入失败（不影响 403 响应）：table=log_audit cause={}", e.getMessage(), e);
        }
    }

    /**
     * 取客户端 IP（优先 {@code X-Forwarded-For} 首段）。
     *
     * @param request 请求
     * @return IP；不可用返回 null
     */
    private static String clientIp(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        return request.getRemoteAddr();
    }
}
