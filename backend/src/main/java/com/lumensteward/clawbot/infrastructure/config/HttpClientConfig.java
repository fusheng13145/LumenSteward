package com.lumensteward.clawbot.infrastructure.config;

import com.lumensteward.clawbot.infrastructure.observability.TraceContext;
import com.lumensteward.clawbot.infrastructure.observability.TraceIdFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * 出站 HTTP 客户端配置（G-16 配置类收敛 / 8.2 traceId 透传）。
 *
 * <p>统一出站超时，并在每个出站请求注入 {@code X-Trace-Id}，使外部依赖侧日志可与本系统链路对齐
 * （FR-21）。LLM/物流/地图等具体客户端的熔断与重试由 Resilience4j 在 T03 装配。
 */
@Configuration
public class HttpClientConfig {

    /** 建连超时（秒）：外部依赖不可达时尽快失败，避免线程堆积。 */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    /** 读超时（秒）：覆盖 LLM 视觉 20s 上限（9.4.1）。 */
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(20);

    /**
     * 共享的 {@link RestClient.Builder}，供 T03 各 SPI 实现注入使用。
     *
     * @return 预置超时与 traceId 透传拦截器的 builder
     */
    @Bean
    public RestClient.Builder clawbotRestClientBuilder() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(CONNECT_TIMEOUT);
        requestFactory.setReadTimeout(READ_TIMEOUT);

        // 出站透传 traceId（8.2）；若调用方已显式设置则不覆盖
        ClientHttpRequestInterceptor tracePropagation = (request, body, execution) -> {
            String traceId = TraceContext.getTraceId();
            if (traceId != null && !request.getHeaders().containsKey(TraceIdFilter.TRACE_ID_HEADER)) {
                request.getHeaders().add(TraceIdFilter.TRACE_ID_HEADER, traceId);
            }
            return execution.execute(request, body);
        };

        return RestClient.builder()
                .requestFactory(requestFactory)
                .requestInterceptor(tracePropagation);
    }
}
