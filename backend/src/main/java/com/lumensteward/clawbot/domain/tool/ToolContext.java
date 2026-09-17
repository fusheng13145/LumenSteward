package com.lumensteward.clawbot.domain.tool;

import java.util.Collections;
import java.util.Map;

/**
 * 工具执行上下文（架构 5.1）。
 *
 * @param traceId    链路标识（与 {@code tool_call_log.trace_id}、响应头 {@code X-Trace-Id} 一致，FR-21）
 * @param openid     发起用户（敏感，日志须脱敏）
 * @param sessionId  会话 id
 * @param llmRound   所属 Agent Loop 轮次（SC-01）
 * @param attributes 附加属性（如任务槽位、来源 IP），只读
 */
public record ToolContext(String traceId, String openid, Long sessionId, int llmRound,
                          Map<String, Object> attributes) {

    public ToolContext {
        attributes = attributes == null ? Map.of() : Collections.unmodifiableMap(attributes);
    }

    /** 便捷构造：无附加属性。 */
    public static ToolContext of(String traceId, String openid, Long sessionId, int llmRound) {
        return new ToolContext(traceId, openid, sessionId, llmRound, Map.of());
    }
}
