package com.lumensteward.clawbot.domain.intent;

import com.lumensteward.clawbot.infrastructure.client.llm.dto.ChatMessage;

import java.util.List;

/**
 * 意图分类器端口（架构 5.2 / SRS FR-05）。
 */
public interface IntentClassifier {

    /**
     * 分类用户意图。
     *
     * @param history     历史消息（可空）
     * @param userMessage 当前用户消息
     * @return 意图结果（无法判定时返回 UNKNOWN，由上层追问）
     */
    IntentResult classify(List<ChatMessage> history, String userMessage);
}
