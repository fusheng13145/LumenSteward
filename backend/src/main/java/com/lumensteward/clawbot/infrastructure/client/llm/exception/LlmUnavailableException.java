package com.lumensteward.clawbot.infrastructure.client.llm.exception;

/**
 * LLM 服务不可用（{@code 40005}，SRS 9.5：连接异常 / 上游 5xx）。
 */
public class LlmUnavailableException extends LlmException {

    public LlmUnavailableException(String message) {
        super(message);
    }

    public LlmUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    @Override
    public String errorType() {
        return "LLM_UNAVAILABLE";
    }

    @Override
    public int errorCode() {
        return 40005;
    }
}
