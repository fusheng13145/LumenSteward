package com.lumensteward.clawbot.application.orchestrator.model;

import com.lumensteward.clawbot.common.enums.SessionState;

import java.util.List;

/**
 * 编排结果（架构 5.2 / SRS 9.4.3 输出）。
 *
 * @param replyText     面向用户的最终回复（已经过兜底与一致性校验）
 * @param finalState    会话最终状态
 * @param executedTools 本次链路已执行的工具记录
 * @param fallbackReason 降级原因（未降级为 null；BR-24）
 * @param llmCalls      LLM 调用次数
 * @param rounds        Agent Loop 轮次
 */
public record OrchestrationResult(String replyText, SessionState finalState,
                                  List<ToolCallRecord> executedTools, String fallbackReason,
                                  int llmCalls, int rounds) {

    public OrchestrationResult {
        executedTools = executedTools == null ? List.of() : List.copyOf(executedTools);
    }

    /** 是否发生降级。 */
    public boolean degraded() {
        return fallbackReason != null && !fallbackReason.isBlank();
    }
}
