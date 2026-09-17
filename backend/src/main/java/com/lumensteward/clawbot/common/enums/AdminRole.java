package com.lumensteward.clawbot.common.enums;

import lombok.Getter;

import java.util.Optional;

/**
 * 后台管理员角色（对齐 {@code sys_admin_user.role} 字段 COMMENT，7.6.3；权限矩阵见 SRS 3.3）。
 *
 * <p>与前端 {@code src/config/permissions.ts}、{@code src/utils/constants.ts} 中的角色定义同源。
 */
@Getter
public enum AdminRole {

    /** 系统管理员：全量读 + 配置写 + 用户禁用。 */
    SUPER_ADMIN("SUPER_ADMIN", "系统管理员"),
    /** 运营管理员：用户/档案/会话读 + 档案写；不可改配置。 */
    OPERATOR("OPERATOR", "运营管理员"),
    /** 审计员：日志与统计只读；不可修改任何业务数据。 */
    AUDITOR("AUDITOR", "审计员");

    /** 落库字符串值。 */
    private final String code;

    /** 中文含义。 */
    private final String label;

    AdminRole(String code, String label) {
        this.code = code;
        this.label = label;
    }

    /** Spring Security 权限名（供 {@code hasRole} 使用）。 */
    public String authority() {
        return "ROLE_" + code;
    }

    /**
     * 按落库值查找角色。
     *
     * @param code 落库值（SUPER_ADMIN/OPERATOR/AUDITOR）
     * @return 匹配的角色
     */
    public static Optional<AdminRole> findByCode(String code) {
        if (code == null) {
            return Optional.empty();
        }
        for (AdminRole role : values()) {
            if (role.code.equalsIgnoreCase(code)) {
                return Optional.of(role);
            }
        }
        return Optional.empty();
    }
}
