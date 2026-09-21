package com.lumensteward.clawbot.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * LLM 调用 token 计量实体，映射 {@code log_llm_call}（B-4 / FR-20 ③，迭代 4 W5）。
 *
 * <p>此前 token 只进 Redis 计数桶（TTL 到次日零点），<b>明细不可复核</b>；本表使「每次调用一行」
 * 成为可查询事实，成本看板的趋势 / 按模型 / 按用途 / 按用户四类聚合都以它为唯一数据源。
 *
 * <p>表只增不改；{@code openid} 仅存<b>脱敏形态</b>（BR-21，脱敏集中在写入侧完成）；
 * {@code totalTokens} 由 prompt+completion 推导，与 Redis 日预算计数同口径。
 */
@Data
@TableName("log_llm_call")
public class LlmCallEntity {

    /** 主键。 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 调用用途（CHAT / MEMORY_EXTRACT / INTENT）。 */
    @TableField("purpose")
    private String purpose;

    /** 供应商标识（mock / openai-compatible）。 */
    @TableField("provider")
    private String provider;

    /** 模型名（请求未显式指定时为 null）。 */
    @TableField("model")
    private String model;

    /** 脱敏后的用户标识（可空：无用户归属的调用）。 */
    @TableField("openid")
    private String openid;

    /** 会话 id（可空）。 */
    @TableField("session_id")
    private Long sessionId;

    /** 链路标识（可空）。 */
    @TableField("trace_id")
    private String traceId;

    /** 输入 token。 */
    @TableField("prompt_tokens")
    private Integer promptTokens;

    /** 输出 token。 */
    @TableField("completion_tokens")
    private Integer completionTokens;

    /** 合计 token。 */
    @TableField("total_tokens")
    private Integer totalTokens;

    /** 创建时间（自动填充）。 */
    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
