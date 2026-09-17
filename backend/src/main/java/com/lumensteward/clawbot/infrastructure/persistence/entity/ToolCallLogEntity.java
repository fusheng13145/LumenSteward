package com.lumensteward.clawbot.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工具调用日志实体，映射表 {@code log_tool_call}（SRS 7.2 表 7-5，表名按 7.6.1 校准）。
 *
 * <p>只增不改（无 {@code updated_at}）。{@code paramsJson}/{@code resultJson} 为 JSON 列，
 * 实体侧以 {@link String} 承载。该表为<b>同步写入</b>（ADR-003），是执行一致性校验的事实依据。
 */
@Data
@TableName("log_tool_call")
public class ToolCallLogEntity {

    /** 主键。 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 链路标识（与响应头 X-Trace-Id 同源）。 */
    @TableField("trace_id")
    private String traceId;

    /** 发起用户。 */
    @TableField("openid")
    private String openid;

    /** 会话（逻辑外键 → wx_session.id）。 */
    @TableField("session_id")
    private Long sessionId;

    /** 工具名。 */
    @TableField("tool_name")
    private String toolName;

    /** 本次链路中的调用序号。 */
    @TableField("call_seq")
    private Integer callSeq;

    /** 入参（JSON 字符串，敏感字段脱敏后存储）。 */
    @TableField("params_json")
    private String paramsJson;

    /** 结果（JSON 字符串，大结果可截断）。 */
    @TableField("result_json")
    private String resultJson;

    /** 状态：0-成功 1-失败 2-降级 3-超时 4-未执行。 */
    @TableField("status")
    private Integer status;

    /** 异常分类：L1/L2/L3/L4（对应 2.3.5 四层分类）。 */
    @TableField("error_type")
    private String errorType;

    /** 降级原因（降级时必填）。 */
    @TableField("fallback_reason")
    private String fallbackReason;

    /** 耗时（ms）。 */
    @TableField("latency_ms")
    private Integer latencyMs;

    /** 所属 Agent Loop 轮次。 */
    @TableField("llm_round")
    private Integer llmRound;

    /** 创建时间（自动填充）。 */
    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
