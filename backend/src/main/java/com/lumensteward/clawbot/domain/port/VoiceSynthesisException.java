package com.lumensteward.clawbot.domain.port;

/**
 * 语音合成端口异常（domain 层）。
 */
public class VoiceSynthesisException extends RuntimeException {

    public VoiceSynthesisException(String message) {
        super(message);
    }

    public VoiceSynthesisException(String message, Throwable cause) {
        super(message, cause);
    }
}
