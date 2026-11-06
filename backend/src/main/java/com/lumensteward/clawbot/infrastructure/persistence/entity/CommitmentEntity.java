package com.lumensteward.clawbot.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 承诺实体（迭代 4 W7/W8，表 {@code biz_commitment}，迁移 V1.0.20）。
 *
 * <p>投递闩走 {@code CommitmentMapper} 的条件 UPDATE，不经实体回写——
 * 「至多一次投递」必须在 SQL 层原子生效（与长任务通道同口径）。
 */
@Data
@TableName("biz_commitment")
public class CommitmentEntity {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("openid")
    private String openid;

    @TableField("content")
    private String content;

    @TableField("due_at")
    private LocalDateTime dueAt;

    @TableField("status")
    private String status;

    @TableField("delivered_at")
    private LocalDateTime deliveredAt;

    @TableField("source_session_id")
    private Long sourceSessionId;

    @TableField("source_trace_id")
    private String sourceTraceId;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
