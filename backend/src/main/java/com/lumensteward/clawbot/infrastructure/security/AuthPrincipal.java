package com.lumensteward.clawbot.infrastructure.security;

import com.lumensteward.clawbot.common.enums.AdminRole;

import java.util.Optional;

/**
 * 已认证管理员主体（架构 5.4 / SRS FR-15）。
 *
 * <p>作为 Spring Security {@code Authentication#getPrincipal()} 的载体，供控制器与审计切面读取
 * 当前操作人及其角色；{@code jti} 用于登出黑名单。
 *
 * @param adminId     管理员主键
 * @param username    登录名
 * @param role        角色落库值（SUPER_ADMIN/OPERATOR/AUDITOR）
 * @param displayName 展示名
 * @param jti         JWT 唯一标识
 */
public record AuthPrincipal(Long adminId, String username, String role, String displayName, String jti) {

    /** 解析角色枚举（非法值返回空）。 */
    public Optional<AdminRole> adminRole() {
        return AdminRole.findByCode(role);
    }

    /** Spring Security 权限名（ROLE_xxx）；角色非法时返回 null。 */
    public String authority() {
        return adminRole().map(AdminRole::authority).orElse(null);
    }
}
