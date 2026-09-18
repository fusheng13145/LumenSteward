package com.lumensteward.clawbot.domain.port;

/**
 * 媒体下发端口异常（domain 层）。
 */
public class MediaDispatchException extends RuntimeException {

    public MediaDispatchException(String message) {
        super(message);
    }

    public MediaDispatchException(String message, Throwable cause) {
        super(message, cause);
    }
}
