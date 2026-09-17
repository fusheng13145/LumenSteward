package com.lumensteward.clawbot.infrastructure.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 链路追踪过滤器（G-09 / 8.2 / BR-30）。
 *
 * <p>在请求入口生成 UUID → 写入 MDC（供日志与 {@code ApiResponse} 读取）→ 写入响应头
 * {@code X-Trace-Id}；请求结束务必清理 MDC，防止 Servlet 线程复用导致 traceId 串号。
 *
 * <p>以 {@link Ordered#HIGHEST_PRECEDENCE} 注册，确保其早于 Spring Security 过滤器链执行，
 * 使鉴权失败的日志同样带 traceId。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    /** 响应头名称；出站调用亦按此名称透传（8.2）。 */
    public static final String TRACE_ID_HEADER = "X-Trace-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String traceId = TraceContext.newTraceId();
        TraceContext.setTraceId(traceId);
        response.setHeader(TRACE_ID_HEADER, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            TraceContext.clear();
        }
    }
}
