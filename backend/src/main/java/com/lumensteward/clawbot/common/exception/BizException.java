package com.lumensteward.clawbot.common.exception;

import com.lumensteward.clawbot.common.error.ErrorCode;
import lombok.Getter;

/**
 * 业务异常（8.6）。
 *
 * <p>所有可预期的业务失败统一以此异常抛出，由 {@code GlobalExceptionHandler} 映射为
 * {@code ApiResponse} + {@link ErrorCode#getHttpStatus()} 对应的 HTTP 状态（禁止一律 500，G-19）。
 */
@Getter
public class BizException extends RuntimeException {

    /** 绑定的错误码（含 HTTP 状态语义）。 */
    private final ErrorCode errorCode;

    public BizException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public BizException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public BizException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    /** 便捷工厂：以错误码默认文案抛出。 */
    public static BizException of(ErrorCode errorCode) {
        return new BizException(errorCode);
    }

    /** 便捷工厂：自定义文案（须已脱敏）。 */
    public static BizException of(ErrorCode errorCode, String message) {
        return new BizException(errorCode, message);
    }
}
