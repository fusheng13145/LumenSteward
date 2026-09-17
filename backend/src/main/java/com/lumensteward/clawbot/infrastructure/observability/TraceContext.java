package com.lumensteward.clawbot.infrastructure.observability;

import org.slf4j.MDC;

import java.util.UUID;

/**
 * 链路上下文（G-09 / 8.2）。
 *
 * <p>封装对 SLF4J {@link MDC} 的读写，保证 traceId 在「响应头 X-Trace-Id」与「响应体 traceId」
 * 与「结构化日志字段 traceId」三处来源唯一。借助 MDC，日志 pattern 可直接引用 {@code %X{traceId}}。
 */
public final class TraceContext {

    /** MDC 键名，与 {@code ApiResponse.MDC_TRACE_ID}、日志 pattern 保持一致。 */
    public static final String TRACE_ID = "traceId";

    private TraceContext() {
        // 工具类禁止实例化
    }

    /**
     * 生成新的 traceId（UUID，形如 {@code 0f8fad5b-d9cb-469f-a165-70867728950e}）。
     *
     * @return 新 traceId
     */
    public static String newTraceId() {
        return UUID.randomUUID().toString();
    }

    /**
     * 读取当前线程的 traceId。
     *
     * @return 当前 traceId；不存在时返回 null
     */
    public static String getTraceId() {
        return MDC.get(TRACE_ID);
    }

    /**
     * 设置当前线程的 traceId。
     *
     * @param traceId traceId
     */
    public static void setTraceId(String traceId) {
        if (traceId != null && !traceId.isBlank()) {
            MDC.put(TRACE_ID, traceId);
        }
    }

    /** 清理当前线程的 traceId（必须在请求结束时调用，避免线程复用串号）。 */
    public static void clear() {
        MDC.remove(TRACE_ID);
    }
}
