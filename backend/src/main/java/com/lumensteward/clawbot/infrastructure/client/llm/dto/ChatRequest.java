package com.lumensteward.clawbot.infrastructure.client.llm.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.List;

/**
 * 对话请求（架构 5.1）。
 *
 * @param model      模型名
 * @param messages   消息序列
 * @param tools      工具 Schema（OpenAI 工具数组形态，可为空）
 * @param toolChoice 工具选择策略（auto/none/required，可为 null）
 * @param stream     是否流式（MVP 恒 false）
 * @param timeout    超时（文本默认 15s，SRS 9.4.1）
 */
public record ChatRequest(String model, List<ChatMessage> messages, List<JsonNode> tools,
                          String toolChoice, boolean stream, Duration timeout) {

    public ChatRequest {
        messages = messages == null ? List.of() : List.copyOf(messages);
        tools = tools == null ? List.of() : List.copyOf(tools);
        timeout = timeout == null ? Duration.ofSeconds(15) : timeout;
    }

    /** 便捷构造：带工具与超时。 */
    public static ChatRequest of(String model, List<ChatMessage> messages,
                                 List<JsonNode> tools, Duration timeout) {
        return new ChatRequest(model, messages, tools, tools == null || tools.isEmpty() ? null : "auto",
                false, timeout);
    }
}
