package com.lumensteward.clawbot.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 管理员实体，映射表 {@code sys_admin_user}（SRS 7.2 表 7-6，表名按 7.6.1 校准）。
 *
 * <p>审计字段 {@code created_at}/{@code updated_at} 为 7.6.2 的「加法补齐」，
 * 由 {@code MyMetaObjectHandler} 自动填充。角色取值与 {@code AdminRole} 同源。
 */
@Data
@TableName("sys_admin_user")
public class SysAdminUserEntity {

    /** 主键。 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 登录名。 */
    @TableField("username")
    private String username;

    /** BCrypt 密码哈希（含盐）。 */
    @TableField("password_hash")
    private String passwordHash;

    /** 显示名。 */
    @TableField("display_name")
    private String displayName;

    /** 角色：SUPER_ADMIN/OPERATOR/AUDITOR（与 {@code AdminRole} 同源）。 */
    @TableField("role")
    private String role;

    /** 状态：1-启用 0-禁用。 */
    @TableField("status")
    private Integer status;

    /** 连续登录失败次数。 */
    @TableField("fail_count")
    private Integer failCount;

    /** 锁定截止时间。 */
    @TableField("locked_until")
    private LocalDateTime lockedUntil;

    /** 上次登录时间。 */
    @TableField("last_login_at")
    private LocalDateTime lastLoginAt;

    /** 上次登录 IP（IPv6 兼容）。 */
    @TableField("last_login_ip")
    private String lastLoginIp;

    /** 创建时间（自动填充）。 */
    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /** 更新时间（自动填充）。 */
    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
