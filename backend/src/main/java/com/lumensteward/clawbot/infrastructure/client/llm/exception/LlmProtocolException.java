package com.lumensteward.clawbot.infrastructure.client.llm.exception;

/**
 * LLM 响应协议非法（{@code 70055}，SRS 9.5：未返回合法 JSON / 结构不符）。
 */
public class LlmProtocolException extends LlmException {

    public LlmProtocolException(String message) {
        super(message);
    }

    public LlmProtocolException(String message, Throwable cause) {
        super(message, cause);
    }

    @Override
    public String errorType() {
        return "LLM_INVALID_OUTPUT";
    }

    @Override
    public int errorCode() {
        return 70055;
    }
}
