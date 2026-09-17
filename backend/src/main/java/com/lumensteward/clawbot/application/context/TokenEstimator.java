package com.lumensteward.clawbot.application.context;

import com.lumensteward.clawbot.infrastructure.client.llm.dto.ChatMessage;

/**
 * token 估算器（架构 5.2 / SRS 9.4.4，Q3：token 预算主控）。
 */
public interface TokenEstimator {

    /**
     * 估算文本 token 数。
     *
     * @param text 文本
     * @return token 数（≥ 0）
     */
    int estimate(String text);

    /**
     * 估算消息 token 数。
     *
     * @param message 消息
     * @return token 数（≥ 0）
     */
    int estimate(ChatMessage message);
}
