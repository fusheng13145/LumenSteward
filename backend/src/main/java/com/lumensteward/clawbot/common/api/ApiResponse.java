package com.lumensteward.clawbot.common.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.lumensteward.clawbot.common.error.ErrorCode;
import lombok.Getter;
import org.slf4j.MDC;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 统一响应体（8.2.1 / G-08）。
 *
 * <p>结构固定为 {@code { code, message, data, traceId, timestamp }}：
 * <ul>
 *   <li>{@code code} 与 HTTP 状态码<b>分层而非互斥</b>：HTTP 表达传输/访问语义，
 *       {@code code} 表达业务结果；业务失败<b>不得</b>一律返回 200。</li>
 *   <li>{@code traceId} 取自 MDC（由 {@code TraceIdFilter} 在入口写入，G-09），
 *       保证响应体与日志、响应头 {@code X-Trace-Id} 三处一致。</li>
 *   <li>时间使用 ISO 8601 带时区（Asia/Shanghai，8.4）。</li>
 * </ul>
 *
 * @param <T> 业务数据类型
 */
@Getter
@JsonInclude(JsonInclude.Include.ALWAYS)
public class ApiResponse<T> {

    /** MDC 中 traceId 的键名，与 TraceIdFilter 保持一致（G-09）。 */
    public static final String MDC_TRACE_ID = "traceId";

    private static final ZoneId ZONE_SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter TS_FORMATTER = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    /** 业务状态码；0 表示成功（8.5）。 */
    private final int code;

    /** 面向调用方的可读信息；禁止包含堆栈/SQL/主机名/密钥（G-13）。 */
    private final String message;

    /** 业务数据；无数据时为 null，但字段始终序列化（契约稳定）。 */
    private final T data;

    /** 链路追踪 ID。 */
    private final String traceId;

    /** 响应时间（ISO 8601 带时区）。 */
    private final String timestamp;

    public ApiResponse(int code, String message, T data) {
        this(code, message, data, MDC.get(MDC_TRACE_ID),
                OffsetDateTime.now(ZONE_SHANGHAI).format(TS_FORMATTER));
    }

    public ApiResponse(int code, String message, T data, String traceId, String timestamp) {
        this.code = code;
        this.message = message;
        this.data = data;
        this.traceId = traceId;
        this.timestamp = timestamp;
    }

    /** 成功（带数据）。 */
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(ErrorCode.SUCCESS.getCode(), ErrorCode.SUCCESS.getMessage(), data);
    }

    /** 成功（无数据）。 */
    public static <T> ApiResponse<T> success() {
        return success(null);
    }

    /** 业务失败：使用错误码枚举自带的 HTTP 语义与文案。 */
    public static <T> ApiResponse<T> error(ErrorCode errorCode) {
        return new ApiResponse<>(errorCode.getCode(), errorCode.getMessage(), null);
    }

    /** 业务失败：使用错误码枚举的状态码 + 自定义文案（文案须已脱敏）。 */
    public static <T> ApiResponse<T> error(ErrorCode errorCode, String message) {
        return new ApiResponse<>(errorCode.getCode(), message, null);
    }

    /** 业务失败：完全自定义 code 与文案（用于第三方转译后的场景）。 */
    public static <T> ApiResponse<T> error(int code, String message) {
        return new ApiResponse<>(code, message, null);
    }
}
