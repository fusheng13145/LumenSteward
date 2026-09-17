package com.lumensteward.clawbot.common.exception;

import com.lumensteward.clawbot.common.error.ErrorCode;

/**
 * 微信回调签名校验失败（SRS L1 接入层，BR-01）。
 *
 * <p>映射为 {@link ErrorCode#FORBIDDEN}（HTTP 403）：拒绝请求且<b>不执行任何业务逻辑</b>
 * （AC-A2：非法签名 100 次全部被拒且业务调用 0 次）。
 */
public class SignatureInvalidException extends BizException {

    public SignatureInvalidException(String message) {
        super(ErrorCode.FORBIDDEN, message);
    }
}
