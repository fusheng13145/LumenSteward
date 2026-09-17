package com.lumensteward.clawbot.infrastructure.client.llm.exception;

/**
 * LLM 调用异常基类（架构 5.1，SRS L2 认知层）。
 *
 * <p>编排器据 {@link #errorType()} 走降级矩阵（SRS 9.5）。子类对应具体错误码：
 * {@code 40001} 超时、{@code 40005} 不可用、{@code 70055} 协议非法。
 */
public abstract class LlmException extends RuntimeException {

    protected LlmException(String message) {
        super(message);
    }

    protected LlmException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * 异常分类标识（用于日志与降级决策）。
     *
     * @return 错误类型
     */
    public abstract String errorType();

    /**
     * 对应的业务错误码数值（与 {@code ErrorCode} 对齐）。
     *
     * @return 错误码
     */
    public abstract int errorCode();
}
