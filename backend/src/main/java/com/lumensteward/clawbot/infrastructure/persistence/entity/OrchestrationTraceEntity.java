package com.lumensteward.clawbot.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 链路时序追踪实体，映射 {@code log_orchestration_trace}（A-5 / T6）。
 *
 * <p>每次编排链路结束落一行：{@code span_json} 保存该链路的 LLM 轮次与工具调用时间轴
 * （{@code List<OrchestrationSpan>} 的 JSON 快照），供管理后台按 {@code traceId} 渲染时序瀑布图。
 *
 * <p>{@code openid} 仅存<b>脱敏形态</b>（如 {@code oabc****wxyz}），不落原始 PII（BR-21）；
 * 表为只增不改（无 {@code updated_at}），{@code created_at} 由 {@code MyMetaObjectHandler} 自动填充。
 */
@Data
@TableName("log_orchestration_trace")
public class OrchestrationTraceEntity {

    /** 主键。 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 链路标识（与响应头 {@code X-Trace-Id} 同源）。 */
    @TableField("trace_id")
    private String traceId;

    /** 脱敏后的用户标识。 */
    @TableField("openid")
    private String openid;

    /** 会话（逻辑外键 → {@code wx_session.id}）。 */
    @TableField("session_id")
    private Long sessionId;

    /** 链路总耗时（ms）。 */
    @TableField("total_ms")
    private Integer totalMs;

    /** 链路总时间预算（ms，SC-03）。 */
    @TableField("total_budget_ms")
    private Integer totalBudgetMs;

    /** Agent Loop 轮次。 */
    @TableField("rounds")
    private Integer rounds;

    /** 是否超预算：true-是 false-否。 */
    @TableField("exceeded_budget")
    private Boolean exceededBudget;

    /** span 数组 JSON（时序瀑布数据）。 */
    @TableField("span_json")
    private String spanJson;

    /** 创建时间（自动填充）。 */
    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
