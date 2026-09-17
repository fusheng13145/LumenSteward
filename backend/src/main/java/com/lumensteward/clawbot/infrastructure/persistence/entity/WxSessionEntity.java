package com.lumensteward.clawbot.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会话实体，映射表 {@code wx_session}（SRS 7.2 表 7-2，表名按 7.6.1 校准）。
 *
 * <p>会话不软删，故无 {@code deleted_at}。审计字段 {@code created_at}/{@code updated_at}
 * 为 7.6.2 的「加法补齐」（SRS 7.2 未定义），由 {@code MyMetaObjectHandler} 自动填充。
 *
 * <p>{@code taskContext} 为 JSON 列，实体侧以 {@link String} 承载，避免引入额外类型处理器。
 */
@Data
@TableName("wx_session")
public class WxSessionEntity {

    /** 主键。 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 会话归属用户。 */
    @TableField("openid")
    private String openid;

    /** Redis 上下文键，格式 conv:{openid}。 */
    @TableField("context_key")
    private String contextKey;

    /** 会话状态：IDLE/CHATTING/TASKING/DEGRADED（与 {@code SessionState} 同源）。 */
    @TableField("state")
    private String state;

    /** 任务型会话槽位缓存（JSON 字符串）。 */
    @TableField("task_context")
    private String taskContext;

    /** 累计轮次（统计用）。 */
    @TableField("turn_count")
    private Integer turnCount;

    /** 最后活跃时间（TTL 依据）。 */
    @TableField("last_active_at")
    private LocalDateTime lastActiveAt;

    /** 创建时间（自动填充）。 */
    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /** 更新时间（自动填充）。 */
    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
