package com.lumensteward.clawbot.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 系统配置实体，映射表 {@code sys_config}（SRS 7.2 表 7-7）。
 *
 * <p>审计字段：{@code updated_at} 为 7.2 原生字段，{@code created_at} 为 7.6.2 加法补齐；
 * 二者均由 {@code MyMetaObjectHandler} 自动填充。
 *
 * <p>MVP 中配置管理为<b>只读展示</b>（PRD Q/P1-06）；{@code SECRET} 类型出参仅返回尾号。
 */
@Data
@TableName("sys_config")
public class SysConfigEntity {

    /** 主键。 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 键名（命名规范 模块.子项，如 llm.model）。 */
    @TableField("config_key")
    private String configKey;

    /** 值（敏感项为 AES 密文）。 */
    @TableField("config_value")
    private String configValue;

    /** 值类型：STRING/INT/DECIMAL/BOOL/JSON/SECRET。 */
    @TableField("value_type")
    private String valueType;

    /** 默认值（用于恢复）。 */
    @TableField("default_value")
    private String defaultValue;

    /** 是否加密：1-是 0-否。 */
    @TableField("is_encrypted")
    private Integer isEncrypted;

    /** 分类：llm/tts/orchestration/security/text/tool。 */
    @TableField("category")
    private String category;

    /** 说明。 */
    @TableField("description")
    private String description;

    /** 修改人（逻辑外键 → sys_admin_user.id）。 */
    @TableField("updated_by")
    private Long updatedBy;

    /** 创建时间（自动填充）。 */
    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /** 更新时间（自动填充）。 */
    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
