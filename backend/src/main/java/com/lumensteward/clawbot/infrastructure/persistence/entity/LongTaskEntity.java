package com.lumensteward.clawbot.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 长任务实体（迭代 4 W15，表 {@code biz_long_task}，迁移 V1.0.19）。
 *
 * <p>状态迁移（claim / complete / markDelivered）全部走 {@code LongTaskMapper} 的
 * 条件 UPDATE，不经实体回写——乐观抢占与幂等闩必须在 SQL 层原子生效。
 */
@Data
@TableName("biz_long_task")
public class LongTaskEntity {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("task_type")
    private String taskType;

    @TableField("openid")
    private String openid;

    @TableField("status")
    private String status;

    @TableField("payload_json")
    private String payloadJson;

    @TableField("result_json")
    private String resultJson;

    @TableField("error_type")
    private String errorType;

    @TableField("attempts")
    private Integer attempts;

    @TableField("delivered_at")
    private LocalDateTime deliveredAt;

    @TableField("trace_id")
    private String traceId;

    @TableField("started_at")
    private LocalDateTime startedAt;

    @TableField("finished_at")
    private LocalDateTime finishedAt;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
