package com.lumensteward.clawbot.infrastructure.client.llm.dto;

import java.util.List;

/**
 * 对话结果（架构 5.1）。
 *
 * @param content      文本内容（可能为空，表示模型仅发起工具调用）
 * @param toolCalls    模型发起的工具调用列表（无则空列表）
 * @param usage        token 计量
 * @param finishReason 结束原因（stop / tool_calls / length）
 * @param rawJson      原始响应 JSON（排障用）
 */
public record ChatResult(String content, List<ToolCall> toolCalls, TokenUsage usage,
                         String finishReason, String rawJson) {

    public ChatResult {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        usage = usage == null ? TokenUsage.EMPTY : usage;
    }

    /** 是否包含工具调用（SRS 9.4.3 第 13 行分支依据）。 */
    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }

    /** 是否包含终态文本。 */
    public boolean hasContent() {
        return content != null && !content.isBlank();
    }

    /** 便捷构造：纯文本结果。 */
    public static ChatResult text(String content) {
        return new ChatResult(content, List.of(), TokenUsage.EMPTY, "stop", null);
    }
}
