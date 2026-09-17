package com.lumensteward.clawbot.application.fallback;

/**
 * 降级原因（架构 5.2 / SRS 9.5 降级矩阵 + 2.3.5 四层异常）。
 *
 * <p>BR-24：但凡发生降级，{@code fallback_reason} 必填（落 {@code log_tool_call.fallback_reason}
 * 与日志）。
 */
public enum FallbackReason {

    /** L1：微信验签失败。 */
    SIGNATURE_FAILED,
    /** L1：消息重复（幂等丢弃）。 */
    MSG_DUPLICATED,
    /** L1：未知消息类型。 */
    UNKNOWN_MSG_TYPE,
    /** L2：LLM 超时（&gt; 15s）。 */
    LLM_TIMEOUT,
    /** L2：LLM 连接失败 / 上游不可用（SRS 9.5：连接异常、5xx）。 */
    LLM_UNAVAILABLE,
    /** L2：LLM 输出格式非法。 */
    LLM_INVALID_OUTPUT,
    /** L2：意图置信度不足（追问澄清）。 */
    LOW_CONFIDENCE_CLARIFY,
    /** L3：工具未注册。 */
    TOOL_NOT_FOUND,
    /** L3：工具参数非法。 */
    INVALID_ARGS,
    /** L3：工具执行失败。 */
    TOOL_FAILED,
    /** L3：工具超时。 */
    TOOL_TIMEOUT,
    /** L3：业务空结果。 */
    EMPTY_RESULT,
    /** L4：执行性幻觉（一致性校验拦截）。 */
    EXECUTION_HALLUCINATION,
    /** L4：内容安全命中。 */
    CONTENT_BLOCKED,
    /** L4：内容安全服务不可用（Fail-Closed）。 */
    SAFETY_UNAVAILABLE,
    /** 基础设施：Redis 不可用（上下文降级为无状态）。 */
    REDIS_UNAVAILABLE,
    /** 基础设施：数据库不可用（只读降级）。 */
    DB_UNAVAILABLE,
    /** 保护：限流。 */
    RATE_LIMITED,
    /** 保护：预算超限。 */
    BUDGET_EXCEEDED,
    /** 编排：强制收敛（达最大轮次）。 */
    FORCED_CONVERGENCE,
    /** 业务：宠物昵称重复。 */
    PET_NAME_DUPLICATE,
    /** 业务：必填槽位缺失（追问）。 */
    MISSING_SLOT;

    /** 是否属于需记录到 {@code fallback_reason} 的降级（BR-24 恒为 true，保留扩展点）。 */
    public boolean recordRequired() {
        return true;
    }
}
