package com.lumensteward.clawbot.infrastructure.client.llm.exception;

/**
 * LLM 调用超时（{@code 40001}，SRS 9.5：耗时 &gt; 15s）。
 */
public class LlmTimeoutException extends LlmException {

    public LlmTimeoutException(String message) {
        super(message);
    }

    public LlmTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }

    @Override
    public String errorType() {
        return "LLM_TIMEOUT";
    }

    @Override
    public int errorCode() {
        return 40001;
    }
}
