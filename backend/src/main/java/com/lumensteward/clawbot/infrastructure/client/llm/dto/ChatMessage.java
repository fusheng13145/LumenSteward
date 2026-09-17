package com.lumensteward.clawbot.infrastructure.client.llm.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 对话消息（架构 5.1，OpenAI 兼容协议 8.1.2）。
 *
 * <p>role 取值 {@code system/user/assistant/tool}；当 role=tool 时 {@code toolCallId} 必填，
 * 且该消息必须紧随其 {@code assistant} 调用消息（上下文裁剪须保持此配对，SRS 9.4.4 第 11-13 行）。
 *
 * @param role       角色
 * @param content    文本内容
 * @param toolCallId 工具调用 id（role=tool 时必填）
 * @param toolCalls  助手发起的工具调用（role=assistant 且需调用工具时）
 * @param name       工具名（role=tool 时用于回注定位）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatMessage(String role, String content, String toolCallId,
                          List<ToolCall> toolCalls, String name) {

    public static final String ROLE_SYSTEM = "system";
    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";
    public static final String ROLE_TOOL = "tool";

    /** 系统提示词消息。 */
    public static ChatMessage system(String content) {
        return new ChatMessage(ROLE_SYSTEM, content, null, null, null);
    }

    /** 用户消息。 */
    public static ChatMessage user(String content) {
        return new ChatMessage(ROLE_USER, content, null, null, null);
    }

    /** 助手文本消息。 */
    public static ChatMessage assistant(String content) {
        return new ChatMessage(ROLE_ASSISTANT, content, null, null, null);
    }

    /** 助手工具调用消息（一个 assistant 对应一到多个 toolCall）。 */
    public static ChatMessage assistantToolCalls(List<ToolCall> toolCalls) {
        return new ChatMessage(ROLE_ASSISTANT, null, null, toolCalls == null ? List.of() : toolCalls, null);
    }

    /** 工具结果回注消息（role=tool，必须携带 toolCallId）。 */
    public static ChatMessage tool(String toolCallId, String name, String content) {
        return new ChatMessage(ROLE_TOOL, content, toolCallId, null, name);
    }

    /** 是否为工具结果消息。 */
    public boolean isTool() {
        return ROLE_TOOL.equals(role);
    }

    /** 是否为助手消息。 */
    public boolean isAssistant() {
        return ROLE_ASSISTANT.equals(role);
    }

    /** 估算用文本长度（content 为空时回退到工具名/调用摘要）。 */
    public int textLength() {
        if (content != null) {
            return content.length();
        }
        if (toolCalls != null && !toolCalls.isEmpty()) {
            int total = 0;
            for (ToolCall call : toolCalls) {
                total += (call.functionName() == null ? 0 : call.functionName().length())
                        + (call.argumentsJson() == null ? 0 : call.argumentsJson().length());
            }
            return total;
        }
        return name == null ? 0 : name.length();
    }
}
