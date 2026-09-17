package com.lumensteward.clawbot.infrastructure.client.llm.dto;

/**
 * Token 计量（架构 5.1 / SRS 9.4.1）。
 *
 * <p>用于 FR-20 成本保护与 FR-17 统计；每次 LLM 调用均记录 prompt/completion/total。
 *
 * @param promptTokens     输入 token
 * @param completionTokens 输出 token
 * @param totalTokens      合计 token
 */
public record TokenUsage(int promptTokens, int completionTokens, int totalTokens) {

    public static final TokenUsage EMPTY = new TokenUsage(0, 0, 0);

    /** 由输入/输出推导合计。 */
    public static TokenUsage of(int promptTokens, int completionTokens) {
        return new TokenUsage(promptTokens, completionTokens, promptTokens + completionTokens);
    }
}
