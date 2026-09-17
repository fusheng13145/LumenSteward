package com.lumensteward.clawbot.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 微信用户实体，映射表 {@code wx_user}（SRS 7.2 表 7-1）。
 *
 * <p>审计字段：{@code created_at}/{@code updated_at} 由 {@code MyMetaObjectHandler} 自动填充
 * （G-04 / 7.6.2），DDL 侧亦有默认值，构成「双重保障」；软删除采用 {@code deleted_at}
 * （NULL 表示未删除），实体侧以 {@link TableLogic} 声明，与 DDL 语义一致。
 */
@Data
@TableName("wx_user")
public class WxUserEntity {

    /** 主键（BIGINT UNSIGNED AUTO_INCREMENT）。 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 微信唯一标识。 */
    @TableField("openid")
    private String openid;

    /** 开放平台 UnionId，未绑定为 null。 */
    @TableField("unionid")
    private String unionid;

    /** 昵称（展示须脱敏）。 */
    @TableField("nickname")
    private String nickname;

    /** 头像链接。 */
    @TableField("avatar_url")
    private String avatarUrl;

    /** 状态：1-正常，0-禁用。 */
    @TableField("status")
    private Integer status;

    /** 最后交互时间（活跃度统计）。 */
    @TableField("last_interact_at")
    private LocalDateTime lastInteractAt;

    /** 创建时间（自动填充）。 */
    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /** 更新时间（自动填充）。 */
    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    /** 软删除时间（NULL 表示未删除）。 */
    @TableLogic(value = "null", delval = "now()")
    @TableField("deleted_at")
    private LocalDateTime deletedAt;
}
