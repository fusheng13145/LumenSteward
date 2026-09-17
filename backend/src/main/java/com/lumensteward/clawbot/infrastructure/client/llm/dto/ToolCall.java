package com.lumensteward.clawbot.infrastructure.client.llm.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 模型发起的工具调用（架构 5.1）。
 *
 * @param id            调用 id（回注 tool 结果时须原样携带）
 * @param type          调用类型，通常为 {@code function}
 * @param functionName  工具名
 * @param argumentsJson 入参 JSON 文本（可能非法，由编排器交给 Schema 校验）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ToolCall(String id, String type, String functionName, String argumentsJson) {

    /** 便捷构造：function 类型。 */
    public static ToolCall function(String id, String functionName, String argumentsJson) {
        return new ToolCall(id, "function", functionName, argumentsJson);
    }
}
