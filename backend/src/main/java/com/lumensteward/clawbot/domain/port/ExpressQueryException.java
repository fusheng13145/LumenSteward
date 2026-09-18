package com.lumensteward.clawbot.domain.port;

/**
 * 快递查询端口异常（domain 层）。
 */
public class ExpressQueryException extends RuntimeException {

    public ExpressQueryException(String message) {
        super(message);
    }

    public ExpressQueryException(String message, Throwable cause) {
        super(message, cause);
    }
}
