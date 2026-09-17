package com.lumensteward.clawbot.application.orchestrator.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.lumensteward.clawbot.common.enums.ToolStatus;
import com.lumensteward.clawbot.domain.tool.ToolResult;

/**
 * 工具调用记录（架构 5.2）。
 *
 * <p><b>对 5.2 签名的必要扩展：</b>为支撑 {@code ToolCallLogService} 的<b>同步落库</b>（ADR-003），
 * 本记录额外携带 {@code id}/{@code traceId}/{@code openid}/{@code sessionId}，使 {@code logEnd}
 * 能按主键更新同一行，而无需借助 ThreadLocal 等副信道。其余字段与 5.2 一致。
 *
 * @param id            落库主键（logStart 后由 DB 回填）
 * @param traceId       链路标识
 * @param openid        发起用户（脱敏后入日志）
 * @param sessionId     会话
 * @param toolName      工具名
 * @param callSeq       本次链路调用序号（体现编排顺序）
 * @param llmRound      所属 Agent Loop 轮次（SC-01）
 * @param status        执行状态
 * @param errorType     异常分类（L1/L2/L3/L4）
 * @param fallbackReason 降级原因（BR-24：降级时必填）
 * @param params        入参
 * @param result        结果
 * @param latencyMs     耗时（ms）
 */
public record ToolCallRecord(Long id, String traceId, String openid, Long sessionId, String toolName,
                             int callSeq, int llmRound, ToolStatus status, String errorType,
                             String fallbackReason, JsonNode params, JsonNode result, long latencyMs) {

    /**
     * 以执行结果收敛本记录（不改变 id/定位字段）。
     *
     * @param result    工具结果
     * @param latencyMs 实际耗时
     * @return 新记录
     */
    public ToolCallRecord withOutcome(ToolResult result, long latencyMs) {
        if (result == null) {
            return new ToolCallRecord(id, traceId, openid, sessionId, toolName, callSeq, llmRound,
                    ToolStatus.FAILED, "TOOL_FAILED", fallbackReason, params, null, latencyMs);
        }
        String error = result.errorType();
        String reason = switch (result.status()) {
            case SUCCESS -> null;
            case DEGRADED -> "TOOL_DEGRADED";
            case TIMEOUT -> "TOOL_TIMEOUT";
            case NOT_EXECUTED -> "NOT_EXECUTED";
            case FAILED -> "TOOL_FAILED";
        };
        return new ToolCallRecord(id, traceId, openid, sessionId, toolName, callSeq, llmRound,
                result.status(), error, reason != null ? reason : fallbackReason,
                params, result.data(), latencyMs);
    }

    /** 是否成功。 */
    public boolean isSuccess() {
        return status == ToolStatus.SUCCESS;
    }
}
