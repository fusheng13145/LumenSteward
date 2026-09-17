package com.lumensteward.clawbot.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 审计日志实体，映射表 {@code log_audit}（SRS 7.2 表 7-8，表名按 7.6.1 校准）。
 *
 * <p>只增不改（无 {@code updated_at}）。{@code beforeValue}/{@code afterValue} 中的
 * 个人信息须脱敏后存储（BR-22）。
 */
@Data
@TableName("log_audit")
public class AuditLogEntity {

    /** 主键。 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 操作人（系统任务为 null，逻辑外键 → sys_admin_user.id）。 */
    @TableField("admin_id")
    private Long adminId;

    /** 资源类型：CONFIG/USER/PROFILE/AUTH/DATA_DELETE。 */
    @TableField("reg_type")
    private String regType;

    /** 操作标识（如 USER_DISABLE/CONFIG_UPDATE）。 */
    @TableField("action")
    private String action;

    /** 操作对象（脱敏标识）。 */
    @TableField("target")
    private String target;

    /** 变更前值（个人信息须脱敏）。 */
    @TableField("before_value")
    private String beforeValue;

    /** 变更后值。 */
    @TableField("after_value")
    private String afterValue;

    /** 变更原因。 */
    @TableField("reason")
    private String reason;

    /** 来源 IP。 */
    @TableField("ip")
    private String ip;

    /** 结果：0-失败 1-成功。 */
    @TableField("result")
    private Integer result;

    /** 创建时间（自动填充）。 */
    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
