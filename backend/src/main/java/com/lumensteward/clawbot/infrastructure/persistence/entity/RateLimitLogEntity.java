package com.lumensteward.clawbot.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 限流事件日志实体，映射 {@code log_rate_limit}（FR-20 ④ / FR-17 可检索）。
 *
 * <p>高频率但低敏感：{@code openid} 仅存储脱敏形态（如 {@code oabcd****wxyz}），
 * 不落原始 PII；监控页按类型/时间/IP 聚合检索。
 */
@Data
@TableName("log_rate_limit")
public class RateLimitLogEntity {

    /** 主键。 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 脱敏后的用户标识。 */
    @TableField("openid")
    private String openid;

    /** 来源 IP。 */
    @TableField("ip")
    private String ip;

    /** 限流类型：USER_FREQ / IP_FREQ。 */
    @TableField("limit_type")
    private String limitType;

    /** 触发时间。 */
    @TableField("hit_at")
    private LocalDateTime hitAt;

    /** 创建时间（自动填充）。 */
    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
