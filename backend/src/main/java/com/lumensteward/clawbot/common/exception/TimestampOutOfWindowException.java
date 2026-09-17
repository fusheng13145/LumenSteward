package com.lumensteward.clawbot.common.exception;

import com.lumensteward.clawbot.common.error.ErrorCode;

/**
 * 微信回调时间戳超出允许窗口（SRS L1 接入层，防重放，AC-A5）。
 *
 * <p>映射为 {@link ErrorCode#FORBIDDEN}（HTTP 403）。
 */
public class TimestampOutOfWindowException extends BizException {

    public TimestampOutOfWindowException(String message) {
        super(ErrorCode.FORBIDDEN, message);
    }
}
