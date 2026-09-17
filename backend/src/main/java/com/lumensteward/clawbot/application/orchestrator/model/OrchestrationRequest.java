package com.lumensteward.clawbot.application.orchestrator.model;

import com.lumensteward.clawbot.infrastructure.client.llm.dto.ChatMessage;

import java.util.List;

/**
 * 编排请求（架构 5.2 / SRS 9.4.3 输入）。
 *
 * @param traceId     链路标识
 * @param openid      用户标识
 * @param sessionId   会话 id
 * @param userMessage 当前用户消息
 * @param history     历史消息（不含 system 与当前 user）
 */
public record OrchestrationRequest(String traceId, String openid, Long sessionId,
                                   String userMessage, List<ChatMessage> history) {

    public OrchestrationRequest {
        history = history == null ? List.of() : List.copyOf(history);
    }
}
