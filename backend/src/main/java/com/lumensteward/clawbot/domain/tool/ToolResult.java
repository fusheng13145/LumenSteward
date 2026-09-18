package com.lumensteward.clawbot.domain.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.lumensteward.clawbot.common.enums.ToolStatus;

/**
 * 工具执行结果（架构 5.1 / SRS 9.4.3 第 42 行）。
 *
 * <p>BR-10：工具内部异常一律转为本结构化的失败结果回注模型，<b>不得</b>静默吞异常，也<b>不得</b>
 * 直接抛出中断链路（关键工具 SC-05 中断由编排器依据 {@link #status()} 判定，而非异常传播）。
 *
 * @param status    执行状态（落库 {@code log_tool_call.status}）
 * @param errorType 异常分类（L3 工具层错误标识，如 TOOL_NOT_FOUND / INVALID_ARGS / TOOL_FAILED）
 * @param message   面向模型的可读说明（须已脱敏，不泄露堆栈/SQL/主机名，G-13）
 * @param data      成功时的结构化结果；失败时为 null
 * @param retryable 是否可自动重试（由工具幂等性决定）
 * @param latencyMs 执行耗时（ms），失败路径可为 0
 */
public record ToolResult(ToolStatus status, String errorType, String message,
                         JsonNode data, boolean retryable, long latencyMs) {

    /** 成功结果。 */
    public static ToolResult success(JsonNode data, long latencyMs) {
        return new ToolResult(ToolStatus.SUCCESS, null, null, data, false, latencyMs);
    }

    /** 失败结果（可重试性由调用方依据幂等性传入）。 */
    public static ToolResult failure(String errorType, String message, boolean retryable) {
        return new ToolResult(ToolStatus.FAILED, errorType, message, null, retryable, 0L);
    }

    /** 降级结果（返回兜底数据，功能收窄但可用）。 */
    public static ToolResult degraded(String errorType, String message, JsonNode data, long latencyMs) {
        return new ToolResult(ToolStatus.DEGRADED, errorType, message, data, false, latencyMs);
    }

    /** 超时结果（SC-03 单工具 8s 上限，SRS L3）。 */
    public static ToolResult timeout(String message) {
        return new ToolResult(ToolStatus.TIMEOUT, "TOOL_TIMEOUT", message, null, true, 0L);
    }

    /** 未执行结果（工具未注册 / 参数非法，SRS 9.4.3 第 25-35 行，幻觉判定依据）。 */
    public static ToolResult notExecuted(String errorType, String message) {
        return new ToolResult(ToolStatus.NOT_EXECUTED, errorType, message, null, false, 0L);
    }

    /** 是否成功（一致性校验仅认可 SUCCESS，SRS 9.4.5 步骤 2）。 */
    public boolean isSuccess() {
        return status == ToolStatus.SUCCESS;
    }

    /**
     * 返回携带可读说明的新结果（不可变 record，原实例不变）。
     *
     * <p>用于在成功路径上补充面向模型的 {@code message}（BR-10 要求结果须带可读说明）。
     * 其余字段原样保留。
     *
     * @param message 面向模型的可读说明（须已脱敏）
     * @return 新的 {@link ToolResult}
     */
    public ToolResult withMessage(String message) {
        return new ToolResult(status, errorType, message, data, retryable, latencyMs);
    }
}
